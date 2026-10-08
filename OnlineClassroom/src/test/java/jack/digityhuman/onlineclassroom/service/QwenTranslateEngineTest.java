package jack.digityhuman.onlineclassroom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.common.api.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 05 FP-05-3 回归：批量失败自动降级逐条（vi 批量 3 连败的 PoC-2 实测场景）。
 * mock RestTemplate 模拟 chat/completions 响应，断言降级路径与失败定位信息。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 共享 stub 的响应构造器不区分用例
class QwenTranslateEngineTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Mock
    private RestTemplate restTemplate;

    private QwenTranslateEngine engine;

    @BeforeEach
    void setUp() {
        engine = new QwenTranslateEngine("sk-test-key", "qwen-flash", restTemplate);
    }

    /** 构造 chat/completions 响应：choices[0].message.content = texts 的 JSON 数组字符串。 */
    private JsonNode responseOf(List<String> texts) {
        ArrayNode array = JSON.createArrayNode();
        texts.forEach(array::add);
        ObjectNode message = JSON.createObjectNode();
        message.put("content", array.toString());
        ArrayNode choices = JSON.createArrayNode();
        choices.add(JSON.createObjectNode().set("message", message));
        return JSON.createObjectNode().set("choices", choices);
    }

    @Test
    void batchSucceedsOnFirstAttemptWithoutPerItemCalls() {
        List<String> texts = List.of("第一句", "第二句");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(responseOf(List.of("譯文一", "譯文二")));

        List<String> result = engine.translateBatch(texts, "ha", "繁體中文");

        assertThat(result).containsExactly("譯文一", "譯文二");
        verify(restTemplate, times(1)).postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    void countMismatchRetriesThenFallsBackToPerItemAndSucceeds() {
        List<String> texts = List.of("第一句", "第二句", "第三句");
        // 前三次批量响应条数不符（模型合并条目），之后逐条响应正确
        List<String> merged = List.of("一", "二"); // 2 != 3
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(responseOf(merged), responseOf(merged), responseOf(merged))
                .thenAnswer(invocation -> {
                    HttpEntity<?> entity = invocation.getArgument(1);
                    String userContent = userContentOf(entity);
                    String original = userContent.substring(userContent.lastIndexOf('['));
                    List<String> input = JSON.readValue(
                            original, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
                    return responseOf(input.stream().map(t -> t + "!译").toList());
                });

        List<String> result = engine.translateBatch(texts, "vi", "Tiếng Việt");

        assertThat(result).hasSize(3).allMatch(t -> t.endsWith("!译"));
        // 3 次批量 + 3 次逐条 = 6 次调用
        verify(restTemplate, times(6)).postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class));
    }

    @Test
    void perItemExhaustionFailsWithIndexInMessage() {
        // 单条输入：批量 3 次失败（条数不符）后逐条也持续失败（模型返回 2 条）
        List<String> texts = List.of("唯一一句");
        List<String> wrong = List.of("错一", "错二");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(responseOf(wrong));

        assertThatThrownBy(() -> engine.translateBatch(texts, "vi", "Tiếng Việt"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 1 条");
    }

    @Test
    void manyTextsFanOutPerItemAfterBatchFailure() {
        // 25 条批量三次失败 → 逐条 25 次成功（验证降级完整覆盖批内所有条目）
        List<String> texts = IntStream.rangeClosed(1, 25).mapToObj(i -> "第" + i + "句").toList();
        List<String> merged = texts.subList(0, 20);
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class)))
                .thenReturn(responseOf(merged), responseOf(merged), responseOf(merged))
                .thenAnswer(invocation -> {
                    HttpEntity<?> entity = invocation.getArgument(1);
                    String userContent = userContentOf(entity);
                    String original = userContent.substring(userContent.lastIndexOf('['));
                    List<String> input = JSON.readValue(
                            original, JSON.getTypeFactory().constructCollectionType(List.class, String.class));
                    return responseOf(input);
                });

        List<String> result = engine.translateBatch(texts, "ha", "繁體中文");

        assertThat(result).containsExactlyElementsOf(texts);
        verify(restTemplate, times(3 + 25)).postForObject(anyString(), any(HttpEntity.class), eq(JsonNode.class));
    }

    /** 从 HttpEntity 体中提取 user 消息内容（模拟请求与逐条回显需要读回输入）。 */
    @SuppressWarnings("unchecked")
    private String userContentOf(HttpEntity<?> entity) {
        var body = (java.util.Map<String, Object>) entity.getBody();
        var messages = (List<java.util.Map<String, Object>>) body.get("messages");
        return (String) messages.get(1).get("content");
    }

    private static String anyString() {
        return org.mockito.ArgumentMatchers.anyString();
    }
}
