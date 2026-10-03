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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * qwen 翻译引擎（MT-03）：DashScope OpenAI 兼容接口 chat/completions，media.translate.model（默认 qwen-flash）。
 *
 * <p>关键对策（02 §6）：prompt 固定"只输出译文 JSON 数组，保持输入序号顺序，不解释"；
 * 输出只取译文文本数组，时间戳/序号由调用方本地回填（不信任模型复制，结构零风险）；
 * 解析失败（非 JSON/长度不符）整批重试 2 次，仍失败抛业务异常置 stage=translate failed。
 */
@Slf4j
@Component
public class QwenTranslateEngine implements TranslateEngine {

    private static final String CHAT_COMPLETIONS_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    /** 02 §4 固定 prompt：只要求译文数组 */
    private static final String SYSTEM_PROMPT = "只输出译文 JSON 数组，保持输入序号顺序，不解释";
    /** 解析失败重试次数（01 §2 非功能需求：qwen 单批失败重试 2 次） */
    private static final int MAX_RETRIES = 2;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final RestTemplate restTemplate;

    public QwenTranslateEngine(
            @Value("${media.translate.api-key:}") String apiKey,
            @Value("${media.translate.model:qwen-flash}") String model,
            @Qualifier("mediaRestTemplate") RestTemplate restTemplate) {
        this.apiKey = apiKey;
        this.model = model;
        this.restTemplate = restTemplate;
    }

    @Override
    public List<String> translateBatch(List<String> texts, String targetLang, String langLabel) {
        if (!StringUtils.hasText(apiKey)) {
            throw new BizException("未配置百炼 API Key（DASHSCOPE_API_KEY），无法翻译字幕");
        }
        BizException lastFailure = null;
        // 共尝试 1+MAX_RETRIES 次：每次整批重新请求，输出仍不符则判定翻译失败
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                List<String> translated = requestOnce(texts, langLabel);
                if (translated.size() != texts.size()) {
                    throw new BizException("译文条数(" + translated.size() + ")与原文(" + texts.size() + ")不符");
                }
                if (attempt > 0) {
                    log.info("qwen 翻译第 {} 次尝试成功 lang={} batch={}", attempt + 1, targetLang, texts.size());
                }
                return translated;
            } catch (BizException exception) {
                lastFailure = exception;
                log.warn("qwen 翻译批次失败(第 {} 次) lang={}: {}", attempt + 1, targetLang, exception.getMessage());
            }
        }
        throw lastFailure == null ? new BizException("qwen 翻译失败") : lastFailure;
    }

    /** 单次 chat/completions 调用：user 消息=目标语言+原文 JSON 数组；解析 choices[0].message.content。 */
    private List<String> requestOnce(List<String> texts, String langLabel) {
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
            JsonNode response = restTemplate.postForObject(
                    CHAT_COMPLETIONS_URL, new HttpEntity<>(body, headers), JsonNode.class);
            String content = response == null ? ""
                    : response.path("choices").path(0).path("message").path("content").asText("");
            return parseArray(content);
        } catch (RuntimeException exception) {
            throw new BizException("qwen 翻译调用失败: " + exception.getMessage());
        }
    }

    /** 模型输出解析：剥掉 markdown 代码围栏后读 JSON 字符串数组。 */
    private List<String> parseArray(String content) {
        String cleaned = content == null ? "" : content.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int fence = cleaned.lastIndexOf("```");
            if (fence >= 0) {
                cleaned = cleaned.substring(0, fence);
            }
            cleaned = cleaned.trim();
        }
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

    private List<String> toList(JsonNode array) {
        var result = new java.util.ArrayList<String>();
        for (JsonNode item : array) {
            result.add(item.asText());
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
