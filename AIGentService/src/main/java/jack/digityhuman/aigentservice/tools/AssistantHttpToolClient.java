package jack.digityhuman.aigentservice.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.aigentservice.engine.AgentTool;
import jack.digityhuman.aigentservice.engine.ToolContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** AtualCombatTraining 内部工具端点的 HTTP adapter。 */
@Component
public class AssistantHttpToolClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final HttpClient httpClient;

    public AssistantHttpToolClient(
            ObjectMapper mapper,
            @Value("${agent.tools.base-url:http://localhost:8082}") String baseUrl,
            @Value("${agent.tools.connect-timeout-ms:5000}") long connectTimeoutMs) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    public List<AgentTool> tools() {
        return List.of(
                tool("create_topic", "创建或重命名训练主题草稿", Map.of(
                        "topic", "string", "description", "string")),
                tool("update_artifact", "增量更新主题工作区产物", Map.of(
                        "patches", "array:object")),
                tool("generate_theme", "受理主题生成任务", Map.of(
                        "goal", "string", "audiences", "array:string", "materialSummary", "string",
                        "keepExisting", "boolean")),
                tool("generate_cases", "受理案例生成任务", Map.of(
                        "scenarioIds", "array:string", "difficulty", "integer"))
        );
    }

    private AgentTool tool(String name, String description, Map<String, String> fields) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public Map<String, Object> parameters() {
                Map<String, Object> properties = new java.util.LinkedHashMap<>();
                fields.forEach((key, type) -> properties.put(key, schema(type)));
                return Map.of("type", "object", "properties", properties);
            }

            @Override
            public String invoke(String arguments, ToolContext context) {
                return call(name, arguments, context);
            }
        };
    }

    private Map<String, Object> schema(String type) {
        if (type.startsWith("array:")) {
            return Map.of(
                    "type", "array",
                    "items", Map.of("type", type.substring("array:".length())));
        }
        return Map.of("type", type);
    }

    private String call(String name, String arguments, ToolContext context) {
        try {
            String query = "?traceId=" + encode(context.traceId()) + "&sessionId=" + encode(context.sessionId());
            HttpRequest request = HttpRequest.newBuilder(URI.create(
                            baseUrl + "/api/act/internal/tools/" + name + query))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(arguments == null ? "{}" : arguments))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return error("工具 HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = mapper.readTree(response.body());
            if (root.path("code").asInt(0) != 0) {
                return error(root.path("message").asText("工具执行失败"));
            }
            return mapper.writeValueAsString(root.path("data"));
        } catch (Exception e) {
            return error("工具调用失败：" + e.getMessage());
        }
    }

    private String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    private String quote(String value) {
        try {
            return mapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return "\"工具执行失败\"";
        }
    }

    private String encode(String value) throws Exception {
        return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
