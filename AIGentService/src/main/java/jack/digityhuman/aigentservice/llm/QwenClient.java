package jack.digityhuman.aigentservice.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.aigentservice.metrics.AgentMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Qwen OpenAI-compatible chat-completions adapter. */
@Component
public class QwenClient implements LlmClient {

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxTokens;
    private final double temperature;
    private final double topP;
    private final HttpClient httpClient;
    private final AgentMetrics metrics;

    @Autowired
    public QwenClient(
            ObjectMapper mapper,
            @Value("${agent.qwen.base-url}") String baseUrl,
            @Value("${agent.qwen.api-key:}") String apiKey,
            @Value("${agent.qwen.model:qwen3.8-flash}") String model,
            @Value("${agent.qwen.max-tokens:104857}") int maxTokens,
            @Value("${agent.qwen.temperature:0.3}") double temperature,
            @Value("${agent.qwen.top-p:0.3}") double topP,
            @Value("${agent.qwen.connect-timeout-ms:10000}") long connectTimeoutMs,
            @Value("${agent.qwen.read-timeout-ms:120000}") long readTimeoutMs,
            AgentMetrics metrics) {
        this.mapper = mapper;
        this.baseUrl = trimSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
        this.maxTokens = maxTokens;
        this.temperature = temperature;
        this.topP = topP;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.readTimeoutMs = readTimeoutMs;
        this.metrics = metrics;
    }

    public QwenClient(
            ObjectMapper mapper,
            String baseUrl,
            String apiKey,
            String model,
            int maxTokens,
            double temperature,
            double topP,
            long connectTimeoutMs,
            long readTimeoutMs) {
        this(mapper, baseUrl, apiKey, model, maxTokens, temperature, topP,
                connectTimeoutMs, readTimeoutMs, null);
    }

    private final long readTimeoutMs;

    @Override
    public LlmResponse complete(LlmRequest request) {
        long startedAt = System.currentTimeMillis();
        if (apiKey == null || apiKey.isBlank()) {
            recordLlm("configuration_error", startedAt);
            return new LlmResponse("", List.of(), "Qwen API key 未配置");
        }
        try {
            ObjectNode body = buildBody(request);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                    .timeout(Duration.ofMillis(readTimeoutMs))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                recordLlm(classifyStatus(response.statusCode()), startedAt);
                return new LlmResponse("", List.of(),
                        "Qwen HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            LlmResponse parsed = parse(response.body());
            recordLlm(parsed.error() == null ? "success" : "invalid_response", startedAt);
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordLlm("interrupted", startedAt);
            return new LlmResponse("", List.of(), "Qwen 调用被中断");
        } catch (HttpTimeoutException e) {
            recordLlm("timeout", startedAt);
            return new LlmResponse("", List.of(), "Qwen 调用超时：" + e.getMessage());
        } catch (Exception e) {
            recordLlm("transport_error", startedAt);
            return new LlmResponse("", List.of(), "Qwen 调用失败：" + e.getMessage());
        }
    }

    private String classifyStatus(int statusCode) {
        if (statusCode == 429) {
            return "rate_limited";
        }
        if (statusCode == 408 || statusCode == 504) {
            return "timeout";
        }
        if (statusCode >= 500) {
            return "server_error";
        }
        return statusCode >= 400 ? "client_error" : "http_error";
    }

    private void recordLlm(String outcome, long startedAt) {
        if (metrics != null) {
            metrics.recordLlm(outcome, System.currentTimeMillis() - startedAt);
        }
    }

    private ObjectNode buildBody(LlmRequest request) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        body.put("top_p", topP);
        body.put("max_tokens", maxTokens);
        ArrayNode messages = body.putArray("messages");
        for (LlmMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role());
            if (message.content() != null && !message.content().isBlank()) {
                node.put("content", message.content());
            } else {
                node.put("content", "");
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (!message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (LlmToolCall call : message.toolCalls()) {
                    ObjectNode item = calls.addObject();
                    item.put("id", call.id());
                    item.put("type", "function");
                    ObjectNode function = item.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
        }
        if (!request.tools().isEmpty()) {
            ArrayNode tools = body.putArray("tools");
            for (ToolDefinition definition : request.tools()) {
                ObjectNode entry = tools.addObject();
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", definition.name());
                function.put("description", definition.description());
                function.set("parameters", mapper.valueToTree(definition.parameters()));
            }
            body.put("tool_choice", "auto");
        }
        if (request.responseSchema() != null && !request.responseSchema().isEmpty()) {
            ObjectNode responseFormat = body.putObject("response_format");
            responseFormat.put("type", "json_schema");
            ObjectNode jsonSchema = responseFormat.putObject("json_schema");
            jsonSchema.put("name", "strict_output");
            jsonSchema.put("strict", true);
            jsonSchema.set("schema", mapper.valueToTree(request.responseSchema()));
        }
        return body;
    }

    private LlmResponse parse(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        String content = message.path("content").asText("");
        List<LlmToolCall> calls = new ArrayList<>();
        for (JsonNode item : message.path("tool_calls")) {
            JsonNode function = item.path("function");
            calls.add(new LlmToolCall(
                    item.path("id").asText(),
                    function.path("name").asText(),
                    function.path("arguments").asText("{}")));
        }
        String finish = choice.path("finish_reason").asText("");
        if (calls.isEmpty() && content.isBlank() && !finish.equals("stop")) {
            return new LlmResponse("", List.of(), "Qwen 未返回有效内容（finish=" + finish + "）");
        }
        return new LlmResponse(content, calls, null);
    }

    private String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ").trim();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "...";
    }
}
