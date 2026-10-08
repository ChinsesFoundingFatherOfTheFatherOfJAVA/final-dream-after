package jack.digityhuman.speechtraining.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import jack.digityhuman.speechtraining.common.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语气维度评分实现（FP-30 Phase 2，PoC 已验证）。
 * 对每条进入评分的语音轮调用千问AI平台音频模型 3 次取中，再按各轮识别字数加权。
 */
@Slf4j
@Service
public class ToneScoringServiceImpl implements ToneScoringService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);
    private static final int RUNS_PER_ROUND = 3;
    private static final double TEMPERATURE = 0.1;
    private static final int MAX_EVIDENCE_LENGTH = 300;
    private static final AtomicInteger POOL_SEQUENCE = new AtomicInteger();

    private static final Map<String, String> MIME_BY_EXTENSION = Map.of(
            ".wav", "audio/wav",
            ".mp3", "audio/mpeg",
            ".flac", "audio/flac",
            ".m4a", "audio/mp4",
            ".ogg", "audio/ogg",
            ".aac", "audio/aac");

    private static final String PROMPT = """
            你是话术训练的语音表现评审。听这段学员录音（ASR 转写文本：{text}），
            只从"{aspect}"这一个角度给 0-100 整数分。
            评分锚点（按可听特征计分，逐条对照）：
            - 80-100：{aspect}特征密集出现——3 处及以上明确的语调上扬/重音强调/能量抬升段落，且贯穿全程，听感有感染力
            - 60-79：{aspect}特征存在但稀疏——1~2 处明确特征，其余段落平稳，整体合格不突出
            - 40-59：{aspect}特征基本缺失——全程单一节奏/单一音量，仅个别词有轻微变化，但表达仍完整清晰
            - 0-39：与{aspect}相悖——特征完全不存在且呈现相反气质（如冷漠、迟疑、破碎）
            判定纪律：
            1. 只统计听到的声学证据（语调起伏、重音、音量变化、语速变化、停顿控制），逐条对照上面锚点后给分；
            2. 庄重、沉稳、从容的风格不等于差——若其停顿控制有力、吐字笃定，按其与"{aspect}"的契合度计分，不因风格严肃而扣分；
            3. 转写文本仅作内容参考，不作为评分依据；录音中的掌声、噪声等非人声忽略；
            4. 打分克制：没有充分证据不要给两端极值。
            只输出 JSON：{"score": N, "evidence": "列出你统计到的特征条数与位置（如：第2句重音、结尾上扬共2处），一句话"}
            """;

    private final RestTemplate toneRestTemplate;
    private final FileStorageService fileStorageService;

    @Value("${speech.tone.enabled:true}")
    private boolean enabled = true;
    @Value("${speech.tone.base-url:}")
    private String baseUrl;
    @Value("${speech.tone.model:qwen3.8-omni-flash}")
    private String model;
    @Value("${speech.tone.api-key:}")
    private String apiKey;
    @Value("${speech.tone.concurrency:3}")
    private int concurrency = 3;

    public ToneScoringServiceImpl(@Qualifier("toneRestTemplate") RestTemplate toneRestTemplate,
                                  FileStorageService fileStorageService) {
        this.toneRestTemplate = toneRestTemplate;
        this.fileStorageService = fileStorageService;
    }

    @Override
    public Path resolveAudioFile(String audioUrl) {
        return fileStorageService.resolveAudioPath(audioUrl);
    }

    /**
     * 语气维度评分。任何一轮没有有效结果 → 整维度返回 null，绝不编造分数。
     */
    @Override
    public MetricOutcome measureTone(DimensionMetricConfig metric, List<ToneRoundInput> rounds) {
        if (!enabled) {
            log.warn("语气评分已关闭，tone 维度本次不参与评分");
            return null;
        }
        if (metric == null || !DimensionMetricConfig.METRIC_TONE.equals(metric.metric())) {
            return null;
        }
        if (rounds == null || rounds.isEmpty()) {
            log.warn("语气评分没有可用的语音轮，tone 维度本次不参与评分");
            return null;
        }

        List<ToneRoundInput> available = new ArrayList<>();
        for (ToneRoundInput round : rounds) {
            if (round == null || round.audioFile() == null) {
                Long messageId = round == null ? null : round.messageId();
                log.warn("语气评分跳过缺失音频路径的轮次, messageId={}", messageId);
                continue;
            }
            if (!Files.isRegularFile(round.audioFile())) {
                log.warn("语气评分跳过不存在的音频文件, messageId={}, path={}",
                        round.messageId(), round.audioFile());
                continue;
            }
            available.add(round);
        }
        if (available.isEmpty()) {
            return null;
        }

        String aspect = DimensionMetricConfig.normalizeToneAspect(
                metric.stringParam("aspect", DimensionMetricConfig.DEFAULT_TONE_ASPECT));
        int poolSize = Math.max(1, Math.min(Math.max(concurrency, 1), available.size()));
        ExecutorService executor = Executors.newFixedThreadPool(poolSize, runnable -> {
            Thread thread = new Thread(runnable, "tone-score-" + POOL_SEQUENCE.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });

        try {
            List<Future<RoundResult>> futures = new ArrayList<>(available.size());
            for (ToneRoundInput round : available) {
                futures.add(executor.submit(() -> scoreRound(round, aspect)));
            }

            List<RoundResult> results = new ArrayList<>(available.size());
            for (Future<RoundResult> future : futures) {
                RoundResult result = future.get();
                if (result == null) {
                    log.warn("语气评分有轮次未取得有效结果，tone 维度本次不参与评分");
                    return null;
                }
                results.add(result);
            }

            int totalChars = results.stream().mapToInt(RoundResult::charCount)
                    .filter(count -> count > 0).sum();
            double weightedSum = 0;
            if (totalChars > 0) {
                for (RoundResult result : results) {
                    if (result.charCount() > 0) {
                        weightedSum += result.score() * result.charCount();
                    }
                }
            } else {
                totalChars = results.size();
                weightedSum = results.stream().mapToInt(RoundResult::score).sum();
            }
            int score = (int) Math.round(weightedSum / totalChars);
            String evidence = results.stream().map(RoundResult::evidence)
                    .filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
            String evidenceText = evidence.isBlank() ? "模型完成多轮语音特征核验" : evidence;

            String fact = "语音模型从" + aspect + "角度评分 " + score + " 分";
            String comment = "本轮从" + aspect + "角度的语音表现评分为 " + score
                    + " 分，主要依据：" + evidenceText + "。";
            return new MetricOutcome(score, fact, comment, List.of(), List.of());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("语气评分线程被中断，tone 维度本次不参与评分", e);
            return null;
        } catch (ExecutionException e) {
            log.warn("语气评分执行失败，tone 维度本次不参与评分", e.getCause());
            return null;
        } finally {
            executor.shutdownNow();
        }
    }

    private RoundResult scoreRound(ToneRoundInput round, String aspect) {
        List<ScoreAttempt> attempts = new ArrayList<>(RUNS_PER_ROUND);
        for (int run = 1; run <= RUNS_PER_ROUND; run++) {
            ScoreAttempt attempt = scoreOnce(round, aspect, run);
            if (attempt != null && attempt.score() != null) {
                attempts.add(attempt);
            }
        }
        if (attempts.isEmpty()) {
            log.warn("语气评分三次均失败, messageId={}", round.messageId());
            return null;
        }
        if (attempts.size() == 1) {
            log.warn("语气评分仅一次有效，按该次结果使用, messageId={}", round.messageId());
        }

        attempts.sort(Comparator.comparing(ScoreAttempt::score));
        int middle = attempts.size() / 2;
        int median;
        if (attempts.size() % 2 == 1) {
            median = attempts.get(middle).score();
        } else {
            median = (int) Math.round(
                    (attempts.get(middle - 1).score() + attempts.get(middle).score()) / 2.0);
        }
        String evidence = attempts.get(middle).evidence();
        return new RoundResult(median, evidence, Math.max(0, round.charCount()));
    }

    /**
     * 单次模型调用。失败返回 null，由同轮剩余有效结果兜底。
     */
    ScoreAttempt scoreOnce(ToneRoundInput round, String aspect, int run) {
        try {
            String mime = mimeFor(round.audioFile());
            String base64 = Base64.getEncoder().encodeToString(Files.readAllBytes(round.audioFile()));
            String prompt = PROMPT.replace("{text}", textOrPlaceholder(round.text()))
                    .replace("{aspect}", aspect);

            Map<String, Object> audio = Map.of("data", "data:" + mime + ";base64," + base64);
            List<Map<String, Object>> content = List.of(
                    Map.of("type", "text", "text", prompt),
                    Map.of("type", "input_audio", "input_audio", audio));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model);
            payload.put("temperature", TEMPERATURE);
            payload.put("messages", List.of(Map.of("role", "user", "content", content)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            ResponseEntity<Map> response = toneRestTemplate.postForEntity(
                    // 平台切换(10-08)：base-url 配置可带/不带 /chat/completions，代码自动补齐
                    baseUrl.endsWith("/chat/completions") ? baseUrl : baseUrl + "/chat/completions",
                    new HttpEntity<>(payload, headers), Map.class);
            String responseText = extractResponseText(response.getBody());
            Matcher matcher = JSON_OBJECT.matcher(responseText);
            if (!matcher.find()) {
                throw new IllegalArgumentException("模型回复中没有 JSON");
            }
            JsonNode json = MAPPER.readTree(matcher.group());
            JsonNode scoreNode = json.get("score");
            if (scoreNode == null || scoreNode.isNull()) {
                throw new IllegalArgumentException("模型回复缺少 score");
            }
            int score = scoreNode.isNumber()
                    ? (int) Math.round(scoreNode.doubleValue())
                    : (int) Math.round(Double.parseDouble(scoreNode.asText().trim()));
            if (score < 0 || score > 100) {
                throw new IllegalArgumentException("模型分数越界: " + score);
            }
            JsonNode evidenceNode = json.get("evidence");
            String evidence = evidenceNode == null ? "" : evidenceNode.asText("");
            if (evidence.length() > MAX_EVIDENCE_LENGTH) {
                evidence = evidence.substring(0, MAX_EVIDENCE_LENGTH);
            }
            return new ScoreAttempt(score, evidence);
        } catch (Exception e) {
            log.warn("语气模型调用失败, messageId={}, run={}, reason={}",
                    round.messageId(), run, e.getMessage());
            return null;
        }
    }

    private String extractResponseText(Map<?, ?> body) {
        if (body == null) {
            throw new IllegalArgumentException("模型响应为空");
        }
        Object choicesValue = body.get("choices");
        if (!(choicesValue instanceof List<?> choices) || choices.isEmpty()
                || !(choices.get(0) instanceof Map<?, ?> choice)) {
            throw new IllegalArgumentException("模型响应缺少 choices");
        }
        Object messageValue = choice.get("message");
        if (!(messageValue instanceof Map<?, ?> message)) {
            throw new IllegalArgumentException("模型响应缺少 message");
        }
        Object content = message.get("content");
        if (content == null) {
            throw new IllegalArgumentException("模型响应缺少 content");
        }
        return String.valueOf(content);
    }

    private String mimeFor(Path audioFile) {
        String filename = audioFile.getFileName().toString().toLowerCase();
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot);
        return MIME_BY_EXTENSION.getOrDefault(extension, "audio/wav");
    }

    private String textOrPlaceholder(String text) {
        return text == null || text.isBlank() ? "（无转写文本）" : text;
    }

    record ScoreAttempt(Integer score, String evidence) {
    }

    private record RoundResult(int score, String evidence, int charCount) {
    }
}
