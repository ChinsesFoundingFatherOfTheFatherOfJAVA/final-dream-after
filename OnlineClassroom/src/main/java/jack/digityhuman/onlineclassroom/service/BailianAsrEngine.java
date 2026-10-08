package jack.digityhuman.onlineclassroom.service;

import com.alibaba.dashscope.audio.asr.transcription.Transcription;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionQueryParam;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionResult;
import com.alibaba.dashscope.audio.asr.transcription.TranscriptionTaskResult;
import com.alibaba.dashscope.common.TaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 千问AI平台异步转写引擎（MT-03 默认 paraformer-v2，media.asr.model 可切 fun-asr）。
 *
 * <p>平台切换（10-08）：百炼欠费，SDK 调用整体切千问AI平台——DashScope SDK 的
 * {@code Constants.baseHttpApiUrl} 静态值由 media.asr.base-url 配置覆盖
 * （两平台账号/热词数据共享，已实测验证）。
 *
 * <p>流程：wav 上传 OSS 临时对象 → 预签名 URL（15 分钟）→ DashScope Transcription.asyncCall
 * → 自轮询（5s 间隔 / 30 分钟超时，01 §2 非功能需求）→ 拉取结果 JSON → sentences 映射中间格式；
 * 结果即下即用，不保存远端 URL（02 §6：结果 24h 有效期，下载即转 SRT 落盘）。
 * OSS 临时对象在 finally 兜底删除，失败也清理。
 */
@Slf4j
@Component
public class BailianAsrEngine implements AsrEngine {

    /** 轮询间隔 5s（01 §2） */
    private static final long POLL_INTERVAL_MS = 5_000;
    /** 轮询超时 30 分钟（01 §2） */
    private static final long POLL_TIMEOUT_MS = 30 * 60_000;

    private final String apiKey;
    private final String model;
    /** 千问AI平台热词表 ID（05 FP-05-4）：留空不启用；paraformer-v2 热词唯一入口，纠"商机→上机"类口音误识 */
    private final String vocabularyId;
    private final OssTempService ossTempService;
    private final SrtService srtService;

    public BailianAsrEngine(
            @Value("${media.asr.api-key:}") String apiKey,
            @Value("${media.asr.model:paraformer-v2}") String model,
            @Value("${media.asr.vocabulary-id:}") String vocabularyId,
            @Value("${media.asr.base-url:https://maas.qianwenaiapi.com/api/v1}") String baseUrl,
            OssTempService ossTempService,
            SrtService srtService) {
        this.apiKey = apiKey;
        this.model = model;
        this.vocabularyId = vocabularyId;
        this.ossTempService = ossTempService;
        this.srtService = srtService;
        // SDK 全局 base url 覆盖（静态值，进程级）：切千问AI平台；空串保持 SDK 默认（dashscope）
        if (StringUtils.hasText(baseUrl)) {
            com.alibaba.dashscope.utils.Constants.baseHttpApiUrl = baseUrl;
        }
    }

    @Override
    public List<SrtService.Sentence> transcribe(Path wav, String coursewareId) {
        if (!StringUtils.hasText(apiKey)) {
            throw new jack.digityhuman.common.api.BizException(
                    "未配置千问AI平台 API Key（DASHSCOPE_API_KEY），无法生成字幕");
        }
        if (!Files.isRegularFile(wav)) {
            throw new jack.digityhuman.common.api.BizException(
                    "字幕音频中间文件不存在（" + wav + "），请先重试转码以产出 wav");
        }
        String presignedUrl = null;
        try {
            presignedUrl = ossTempService.uploadAndPresign(wav, coursewareId);
            String resultJson = pollUntilDone(presignedUrl);
            List<SrtService.Sentence> sentences = srtService.fromAsrJson(resultJson);
            log.info("千问AI转写完成 coursewareId={} model={} sentences={}", coursewareId, model, sentences.size());
            return sentences;
        } finally {
            // 双保险之一：管线内兜底删除临时对象（另一保险为桶生命周期规则）
            ossTempService.delete(coursewareId);
        }
    }

    /** asyncCall 提交后自轮询 fetch（5s/30min），成功后取首个子任务的 transcription_url 并下载 JSON。 */
    private String pollUntilDone(String fileUrl) {
        try {
            Transcription transcription = new Transcription();
            TranscriptionParam param = buildParam(fileUrl);
            TranscriptionResult submitted = transcription.asyncCall(param);
            String taskId = submitted.getTaskId();
            log.info("千问AI转写任务已提交 taskId={} model={} vocabularyId={}",
                    taskId, model, StringUtils.hasText(vocabularyId) ? "启用" : "无");

            // E2E 联调修正（10-08）：fetch 的 queryParam 必须用官方静态方法从原 param 派生——
            // 手动 builder 只带 taskId+apiKey 缺 model，SDK 内部构造请求直接 NPE"headers cannot be null"
            TranscriptionQueryParam queryParam =
                    TranscriptionQueryParam.FromTranscriptionParam(param, taskId);
            long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS);
                TranscriptionResult result = transcription.fetch(queryParam);
                TaskStatus status = result.getTaskStatus();
                if (status == TaskStatus.SUCCEEDED) {
                    return downloadResult(result);
                }
                if (status == TaskStatus.FAILED || status == TaskStatus.CANCELED) {
                    String message = result.getOutput() == null ? "" : result.getOutput().toString();
                    throw new jack.digityhuman.common.api.BizException("千问AI转写任务失败(" + status + "): " + message);
                }
            }
            throw new jack.digityhuman.common.api.BizException("千问AI转写轮询超时（30 分钟）");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new jack.digityhuman.common.api.BizException("千问AI转写轮询被中断");
        } catch (jack.digityhuman.common.api.BizException exception) {
            throw exception;
        } catch (Exception exception) {
            // DashScope SDK 抛出受检/运行时混合异常，统一收敛为业务失败信息入 error_msg
            throw new jack.digityhuman.common.api.BizException(
                    "千问AI转写调用失败: " + tail(exception.getMessage(), 500));
        }
    }

    /**
     * 转写请求参数构造（package-private 供单测断言热词分支）。
     * FP-05-4：配置了热词表 ID 则随请求传入（录音文件识别 API 的热词唯一入口 vocabulary_id，
     * 必须与创建热词表时的 target_model 完全一致才生效）；未配置不设置该参数，行为与现状一致。
     */
    TranscriptionParam buildParam(String fileUrl) {
        TranscriptionParam.TranscriptionParamBuilder<?, ?> builder = TranscriptionParam.builder()
                .apiKey(apiKey)
                .model(model)
                .fileUrls(List.of(fileUrl));
        if (StringUtils.hasText(vocabularyId)) {
            builder.vocabularyId(vocabularyId);
        }
        return builder.build();
    }

    /** 结果 JSON 即下即用：取 transcription_url 下载文本（公开只读 JSON）。 */
    private String downloadResult(TranscriptionResult result) {
        List<TranscriptionTaskResult> results = result.getResults();
        if (results == null || results.isEmpty()) {
            throw new jack.digityhuman.common.api.BizException("千问AI转写成功但无结果内容");
        }
        String transcriptionUrl = results.get(0).getTranscriptionUrl();
        if (!StringUtils.hasText(transcriptionUrl)) {
            throw new jack.digityhuman.common.api.BizException("千问AI转写结果缺少 transcription_url");
        }
        try (var stream = java.net.URI.create(transcriptionUrl).toURL().openStream()) {
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException exception) {
            throw new jack.digityhuman.common.api.BizException("千问AI转写结果下载失败: " + exception.getMessage());
        }
    }

    /** 异常消息截尾：与 error_msg 落库口径一致（500 字符）。 */
    private String tail(String message, int limit) {
        if (message == null) {
            return "";
        }
        String normalized = message.replaceAll("\\s+", " ").trim();
        return normalized.length() > limit ? normalized.substring(normalized.length() - limit) : normalized;
    }
}
