package jack.digityhuman.onlineclassroom.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 05 FP-05-3 回归 + 平台切换（10-08）：批量失败自动降级逐条；URL 由 base-url 配置拼接。
 * mock RestTemplate 模拟 chat/completions 响应（String 原文，生产侧手动 readTree）。
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
        engine = new QwenTranslateEngine("sk-test-key", "qwen-flash",
                "https://maas.qianwenaiapi.com/compatible-mode/v1", restTemplate);
    }

    /** 构造 chat/completions 响应体字符串：choices[0].message.content = JSON 字符串形态的译文数组
     *  （真实 API 的 content 是字符串；ArrayNode 直接 asText 会得空串，必须双重编码）。 */
    private String responseOf(List<String> texts) throws Exception {
        return "{\"choices\":[{\"message\":{\"content\":"
                + JSON.writeValueAsString(JSON.writeValueAsString(texts)) + "}}]}";
    }

    @Test
    void batchSucceedsOnFirstAttemptWithoutPerItemCalls() throws Exception {
        List<String> texts = List.of("第一句", "第二句");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(responseOf(List.of("譯文一", "譯文二")));

        List<String> result = engine.translateBatch(texts, "ha", "繁體中文");

        assertThat(result).containsExactly("譯文一", "譯文二");
        verify(restTemplate, times(1)).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void countMismatchRetriesThenFallsBackToPerItemAndSucceeds() throws Exception {
        List<String> texts = List.of("第一句", "第二句", "第三句");
        // 前三次批量响应条数不符（模型合并条目），之后逐条响应正确
        String merged = responseOf(List.of("一", "二")); // 2 != 3
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(merged, merged, merged)
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
        verify(restTemplate, times(6)).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void perItemExhaustionFailsWithIndexInMessage() throws Exception {
        // 单条输入：批量 3 次失败（条数不符）后逐条也持续返回空内容 → "第 N 条"失败
        List<String> texts = List.of("唯一一句");
        String merged = responseOf(List.of("一", "二", "三"));
        String empty = responseOfRaw("");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(merged, merged, merged, empty);

        assertThatThrownBy(() -> engine.translateBatch(texts, "vi", "Tiếng Việt"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 1 条");
    }

    @Test
    void perItemAcceptsPlainTextInputWhenModelSkipsJsonArray() throws Exception {
        // E2E 修正回归：模型逐条时返回裸文本（非 JSON 数组，实测"呃…"语气词条目）→ 整段视为译文
        List<String> texts = List.of("第一条", "第二条");
        String merged = responseOf(List.of("x"));
        String plain = responseOfRaw("呃，這是譯文。");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(merged, merged, merged, plain, responseOf(List.of("第二條譯文")));

        List<String> result = engine.translateBatch(texts, "ha", "繁體中文");

        assertThat(result).containsExactly("呃，這是譯文。", "第二條譯文");
    }

    @Test
    void objectArrayItemsAreUnwrappedViaTextField() throws Exception {
        // E2E 修正回归：模型逐条时返回 [{"id":1,"text":"译文"}] 对象数组（此前 asText() 对
        // ObjectNode 返回空串，是 33 条空译文的根因）→ 取 text 字段为译文
        List<String> texts = List.of("那这个就是说呃就非常好了。");
        String objectForm = responseOfRaw("[{\"id\":1,\"text\":\"Well, that means it's really great.\"}]");
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(objectForm);

        List<String> result = engine.translateBatch(texts, "en", "English");

        assertThat(result).containsExactly("Well, that means it's really great.");
    }

    @Test
    void blankItemsInBatchTriggerPerItemFallback() throws Exception {
        // E2E 修正回归：批量返回含空串（模型对短句返回空译文）→ 视为失败降级逐条
        List<String> texts = List.of("第一句", "很短", "第三句");
        String withBlank = responseOf(List.of("譯文一", "", "譯文三"));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(withBlank, withBlank, withBlank,
                        responseOf(List.of("譯文一")), responseOf(List.of("短的譯文")), responseOf(List.of("譯文三")));

        List<String> result = engine.translateBatch(texts, "ha", "繁體中文");

        assertThat(result).containsExactly("譯文一", "短的譯文", "譯文三");
    }

    /** 构造 content 为任意原始字符串（非 JSON 数组）的响应体。 */
    private String responseOfRaw(String content) throws Exception {
        return "{\"choices\":[{\"message\":{\"content\":" + JSON.writeValueAsString(content) + "}}]}";
    }

    @Test
    void manyTextsFanOutPerItemAfterBatchFailure() throws Exception {
        // 25 条批量三次失败 → 逐条 25 次成功（验证降级完整覆盖批内所有条目）
        List<String> texts = IntStream.rangeClosed(1, 25).mapToObj(i -> "第" + i + "句").toList();
        String merged = responseOf(texts.subList(0, 20));
        when(restTemplate.postForObject(anyString(), any(HttpEntity.class), eq(String.class)))
                .thenReturn(merged, merged, merged)
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
        verify(restTemplate, times(3 + 25)).postForObject(anyString(), any(HttpEntity.class), eq(String.class));
    }

    /** 从 HttpEntity 体中提取 user 消息内容（模拟请求与逐条回显需要读回输入）。 */
    @SuppressWarnings("unchecked")
    private String userContentOf(HttpEntity<?> entity) {
        var body = (java.util.Map<String, Object>) entity.getBody();
        var messages = (List<java.util.Map<String, Object>>) body.get("messages");
        return (String) messages.get(1).get("content");
    }
}
