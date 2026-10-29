package jack.digityhuman.atualcombattraining.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 在线课堂（OnlineClassroom，8084）AI 素材抽取客户端
 * <p>
 * 只调一个端点：{@code POST /api/kng/admin/courseware/ai-sources/{id}/extract}。
 * 数据所有权：{@code kng_*} 表与课件源文件只由在线课堂读，本应用不建 Mapper 直读，
 * 需要正文就经本客户端要（与 OrgQueryService 走 Dubbo 的取向一致）。
 * <p>
 * 鉴权：转发当前请求的 Authorization（网关/在线课堂共用 StaffManagement 签发的 JWT），
 * 不在这里自造第三套 token 解析。
 */
@Slf4j
@Component
public class KngAiSourceClient {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;
    private final int timeoutMs;
    private final HttpClient httpClient;

    /**
     * 只注入两个配置项：本工程没有注册 ObjectMapper bean（JacksonConfig 只定制序列化行为），
     * 这里自建一个即可——只用来读在线课堂的 JSON 响应，与全局序列化口径无关。
     */
    public KngAiSourceClient(
            @Value("${kng.ai-source.base-url:http://localhost:8084}") String baseUrl,
            @Value("${kng.ai-source.timeout-ms:120000}") int timeoutMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.timeoutMs = timeoutMs > 0 ? timeoutMs : 120_000;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(10_000))
                .build();
    }

    /**
     * 抽取课件正文。
     *
     * @param coursewareId 课件 id
     * @param purpose      designAssistant / question（决定在线课堂侧的截断上限）
     * @param authorization 当前请求的 Authorization 头（原样转发给在线课堂）
     * @return 抽取结果（正文 + 文件元信息，不含磁盘路径）
     */
    public ExtractedSource extract(String coursewareId, String purpose, String authorization) {
        if (coursewareId == null || coursewareId.isBlank()) {
            throw new BizException("课件 id 不能为空");
        }
        String path = "/api/kng/admin/courseware/ai-sources/" + coursewareId + "/extract?purpose=" + purpose;
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody());
        if (authorization != null && !authorization.isBlank()) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException("在线课堂抽取被中断");
        } catch (IOException e) {
            throw new BizException("在线课堂服务不可达：" + e.getMessage());
        }
        if (response.statusCode() != 200) {
            throw new BizException("在线课堂抽取失败：HTTP " + response.statusCode());
        }
        JsonNode root;
        try {
            root = mapper.readTree(response.body());
        } catch (Exception e) {
            throw new BizException("在线课堂返回内容无法解析");
        }
        // 在线课堂统一 ApiResponse：业务失败是 HTTP 200 + 非零 code，此处把 message 原样抛给调用方
        if (root.path("code").asInt(-1) != 0) {
            throw new BizException(root.path("message").asText("课件抽取失败"));
        }
        JsonNode data = root.path("data");
        if (!data.isObject()) {
            throw new BizException("在线课堂未返回抽取结果");
        }
        ExtractedSource source = new ExtractedSource();
        source.coursewareId = data.path("coursewareId").asText(coursewareId);
        source.coursewareTitle = data.path("coursewareTitle").asText("");
        source.sourceFileId = data.path("sourceFileId").asText("");
        source.fileName = data.path("fileName").asText("");
        source.fileType = data.path("fileType").asText("");
        source.fileSize = data.path("fileSize").asLong(0L);
        source.text = data.path("text").asText("");
        source.chars = data.path("chars").asInt(0);
        source.truncated = data.path("truncated").asBoolean(false);
        if (source.text.isBlank()) {
            throw new BizException("未提取到文本内容");
        }
        log.info("在线课堂素材抽取完成 coursewareId={} purpose={} 字数={} 截断={}",
                coursewareId, purpose, source.chars, source.truncated);
        return source;
    }

    /** 抽取结果（在线课堂侧返回的正文与源文件元信息） */
    public static class ExtractedSource {
        public String coursewareId;
        public String coursewareTitle;
        public String sourceFileId;
        public String fileName;
        public String fileType;
        public long fileSize;
        public String text;
        public int chars;
        public boolean truncated;
    }
}
