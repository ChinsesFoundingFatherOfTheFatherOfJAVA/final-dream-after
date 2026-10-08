package jack.digityhuman.onlineclassroom.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * qwen 翻译引擎（MT-03 + 05 FP-05-3）：OpenAI 兼容接口 chat/completions，
 * media.translate.model（默认 qwen-flash）。
 *
 * <p>平台切换（10-08）：百炼欠费，全部调用切千问AI平台（maas.qianwenaiapi.com，
 * 账号/热词数据两平台共享已验证）；base-url 由 media.translate.base-url 配置，
 * 代码自动补 /chat/completions 后缀（兼容配置带不带后缀两种写法）。
 *
 * <p>关键对策（02 §6 + PoC-2 实测定稿）：
 * <ul>
 *   <li>prompt 固定"只输出译文 JSON 数组，保持输入序号顺序，不解释"；</li>
 *   <li>输出只取译文文本数组，时间戳/序号由调用方本地回填（不信任模型复制，结构零风险）；</li>
 *   <li>批量输出条数对齐不可信（25 条常被并成 26/27）——重试 2 次仍失败<b>自动降级逐条翻译</b>
 *       （vi 实测批量 3 连败、逐条稳定），不再让整批失败终结 stage；</li>
 *   <li>逐条单条失败重试 2 次，仍失败抛业务异常并标注"第 N 条"便于人工定位。</li>
 * </ul>
 */
@Slf4j
@Component
public class QwenTranslateEngine implements TranslateEngine {

    /** 后缀由配置 base-url 自动补齐（兼容带/不带后缀两种配置写法） */
    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    /** 02 §4 固定 prompt：只要求译文数组 */
    private static final String SYSTEM_PROMPT = "只输出译文 JSON 数组，保持输入序号顺序，不解释";
    /** 批量/单条失败重试次数（01 §2 非功能需求：qwen 单批失败重试 2 次） */
    private static final int MAX_RETRIES = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String chatCompletionsUrl;
    private final RestTemplate restTemplate;

    public QwenTranslateEngine(
            @Value("${media.translate.api-key:}") String apiKey,
            @Value("${media.translate.model:qwen-flash}") String model,
            @Value("${media.translate.base-url:https://maas.qianwenaiapi.com/compatible-mode/v1}") String baseUrl,
            @Qualifier("mediaRestTemplate") RestTemplate restTemplate) {
        this.apiKey = apiKey;
        this.model = model;
        this.chatCompletionsUrl = baseUrl.endsWith(CHAT_COMPLETIONS_PATH)
                ? baseUrl : baseUrl + CHAT_COMPLETIONS_PATH;
        this.restTemplate = restTemplate;
    }

    @Override
    public List<String> translateBatch(List<String> texts, String targetLang, String langLabel) {
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException("未配置千问AI平台 API Key（DASHSCOPE_API_KEY），无法翻译字幕");
        }
        // 共尝试 1+MAX_RETRIES 次：每次整批重新请求，输出仍不符则降级逐条（不再直接失败）
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<String> translated = requestOnce(texts, langLabel);
                if (translated.size() != texts.size()) {
                    throw new BizException("译文条数(" + translated.size() + ")与原文(" + texts.size() + ")不符");
                }
                // E2E 联调修正（10-08）：模型批量翻译时会对短句/语气词条目返回空串
                // （实测 en 45/93、vi 18/93 空条目）——空译文等同失败，触发重试/逐条降级
                long blankCount = translated.stream().filter(t -> t == null || t.isBlank()).count();
                if (blankCount > 0) {
                    throw new BizException("译文含 " + blankCount + " 条空文本");
                }
                if (attempt > 0) {
                    log.info("qwen 翻译第 {} 次尝试成功 lang={} batch={}", attempt + 1, targetLang, texts.size());
                }
                return translated;
            } catch (BizException exception) {
                log.warn("qwen 翻译批次失败(第 {} 次) lang={}: {}", attempt + 1, targetLang, exception.getMessage());
            }
        }
        // FP-05-3：批量对齐不可信（PoC-2 实测 vi 批量 3 连败）→ 统一降级逐条，避免整 stage 失败
        log.warn("qwen 翻译批量重试耗尽，降级为逐条翻译 lang={} batch={}", targetLang, texts.size());
        return translateOneByOne(texts, langLabel);
    }

    /**
     * 逐条翻译兜底：每条单独请求（输入/输出天然 1:1，绕开模型合并条目的对齐问题）；
     * 单条重试 2 次仍失败抛"第 N 条"业务异常，失败信息可定位到具体字幕。
     *
     * <p>解析宽容（E2E 联调修正 10-08）：单条场景下模型可能直接返回裸译文文本
     * （非 JSON 数组，实测"呃…"开头的语气词条目），此时整段内容即译文——
     * 仅当输出为空白才判定失败。
     */
    private List<String> translateOneByOne(List<String> texts, String langLabel) {
        List<String> result = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            BizException lastFailure = null;
            for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                try {
                    result.add(requestSingle(text, langLabel));
                    lastFailure = null;
                    break;
                } catch (BizException exception) {
                    lastFailure = exception;
                }
            }
            if (lastFailure != null) {
                throw new BizException("qwen 翻译第 " + (i + 1) + " 条失败: " + lastFailure.getMessage());
            }
        }
        return result;
    }

    /** 单条翻译请求：优先按 JSON 数组解析（恰好 1 条），解析失败时把非空整段内容视为译文。 */
    private String requestSingle(String text, String langLabel) {
        String content = requestContent(List.of(text), langLabel);
        try {
            List<String> parsed = parseArray(content);
            if (parsed.size() == 1) {
                if (parsed.get(0).isBlank()) {
                    // 解析成功但为空串（如对象条目缺 text 字段）：按失败处理触发重试，不静默写空字幕
                    throw new BizException("单条译文为空");
                }
                return parsed.get(0);
            }
        } catch (BizException ignored) {
            // 非数组/条数异常输出：落到裸文本兜底
        }
        String plain = stripFences(content).trim();
        if (plain.isEmpty()) {
            throw new BizException("模型返回为空");
        }
        return plain;
    }

    /** 单次 chat/completions 调用：user 消息=目标语言+原文 JSON 数组；解析 choices[0].message.content。 */
    private List<String> requestOnce(List<String> texts, String langLabel) {
        String content = requestContent(texts, langLabel);
        return parseArray(content);
    }

    /** 发起调用并返回 message.content 原文（收 String 手动 readTree，converter 配置无关）。 */
    private String requestContent(List<String> texts, String langLabel) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("temperature", 0.3);
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", "目标语言：" + langLabel + "\n"
                        + toJson(texts))));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        try {
            // E2E 联调修正（10-08）：Boot4 的 RestTemplate 对抽象类型 JsonNode 反序列化报
            // "Type definition error"，改为收 String 再手动 readTree（converter 配置无关）
            String raw = restTemplate.postForObject(
                    chatCompletionsUrl, new HttpEntity<>(body, headers), String.class);
            String content = "";
            if (StringUtils.hasText(raw)) {
                content = JSON.readTree(raw).path("choices").path(0).path("message").path("content").asText("");
            }
            return content;
        } catch (JsonProcessingException exception) {
            throw new BizException("qwen 翻译响应解析失败: " + exception.getMessage());
        } catch (RuntimeException exception) {
            throw new BizException("qwen 翻译调用失败: " + exception.getMessage());
        }
    }

    /** 剥掉模型输出外层的 markdown 代码围栏（JSON 数组解析与裸文本兜底共用）。 */
    private String stripFences(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) {
                cleaned = cleaned.substring(0, fence);
            }
        }
        return cleaned;
    }

    /** 模型输出解析：剥掉 markdown 代码围栏后读 JSON 字符串数组。 */
    private List<String> parseArray(String content) {
        String cleaned = stripFences(content).trim();
        try {
            JsonNode node = JSON.readTree(cleaned);
            if (!node.isArray()) {
                throw new BizException("qwen 输出不是 JSON 数组");
            }
            return toList(node);
        } catch (JsonProcessingException exception) {
            throw new BizException("qwen 输出解析失败: " + exception.getMessage());
        }
    }

    /**
     * 模型输出数组 → 译文列表（E2E 联调修正 10-08）：兼容三种条目形态——
     * 字符串 {@code ["译文"]}、对象 {@code [{"id":1,"text":"译文"}]}（模型逐条时常见，
     * 此前 asText() 对 ObjectNode 返回空串，是 33 条空译文的根因）、其他标量。
     */
    private List<String> toList(JsonNode array) {
        var result = new java.util.ArrayList<String>();
        for (JsonNode item : array) {
            if (item.isObject()) {
                String text = item.path("text").asText(null);
                if (text == null) {
                    for (JsonNode field : item) {
                        if (field.isTextual() && !field.asText().isBlank()) {
                            text = field.asText();
                            break;
                        }
                    }
                }
                result.add(text == null ? "" : text);
            } else {
                result.add(item.asText());
            }
        }
        return result;
    }

    private String toJson(List<String> texts) {
        try {
            return JSON.writeValueAsString(texts);
        } catch (JsonProcessingException exception) {
            throw new BizException("原文序列化失败");
        }
    }
}
