package jack.digityhuman.speechtraining.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import jack.digityhuman.speechtraining.common.FileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ToneScoringServiceTest {

    private static final String URL = "http://tone.test/chat/completions";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private FileStorageService fileStorageService;

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private ToneScoringService service;
    private Path audioFile;

    @BeforeEach
    void setUp() throws IOException {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.bindTo(restTemplate).build();
        service = new ToneScoringService(restTemplate, fileStorageService);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "baseUrl", URL);
        ReflectionTestUtils.setField(service, "model", "qwen3.8-omni-flash");
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "concurrency", 1);
        audioFile = Files.createTempFile("tone-scoring-", ".wav");
        Files.write(audioFile, new byte[]{1, 2, 3, 4});
    }

    @AfterEach
    void tearDown() throws IOException {
        Files.deleteIfExists(audioFile);
    }

    @Test
    void 三次取中_返回中位数并发送音频请求() throws Exception {
        expectSuccess(30);
        expectSuccess(50);
        expectSuccess(70);

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(round(audioFile, 10)));

        assertEquals(50, outcome.score());
        assertTrue(outcome.comment().contains("热情"));
        assertTrue(outcome.comment().contains("evidence-50"));
        server.verify();
    }

    @Test
    void 三次中一次失败_取剩余结果中位() throws Exception {
        server.expect(requestTo(URL)).andRespond(withServerError());
        expectSuccess(40);
        expectSuccess(60);

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(round(audioFile, 10)));

        assertEquals(50, outcome.score());
        server.verify();
    }

    @Test
    void 仅一次成功_使用该次并告警(CapturedOutput output) throws Exception {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withServerError());
        expectSuccess(55);

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(round(audioFile, 10)));

        assertEquals(55, outcome.score());
        assertTrue(output.getOut().contains("仅一次有效"));
        server.verify();
    }

    @Test
    void 三次全部失败_整维度不参与评分() throws Exception {
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withServerError());
        server.expect(requestTo(URL)).andRespond(withServerError());

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(round(audioFile, 10)));

        assertNull(outcome);
        server.verify();
    }

    @Test
    void 字数加权_10字60分与30字80分得75() throws Exception {
        for (int i = 0; i < 3; i++) {
            expectSuccess(60);
        }
        for (int i = 0; i < 3; i++) {
            expectSuccess(80);
        }

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(
                round(audioFile, 10),
                round(audioFile, 30)));

        assertEquals(75, outcome.score());
        server.verify();
    }

    @Test
    void 文件缺失的轮次跳过_其余轮次照常评分(CapturedOutput output) throws Exception {
        Path missing = audioFile.resolveSibling("missing-tone.wav");
        for (int i = 0; i < 3; i++) {
            expectSuccess(70);
        }

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(
                round(missing, 10),
                round(audioFile, 20)));

        assertEquals(70, outcome.score());
        assertTrue(output.getOut().contains("跳过不存在的音频文件"));
        server.verify();
    }

    @Test
    void 关闭开关_不调用模型且整维度不参与(CapturedOutput output) {
        ReflectionTestUtils.setField(service, "enabled", false);

        MetricOutcome outcome = service.measureTone(toneMetric(), List.of(round(audioFile, 10)));

        assertNull(outcome);
        assertTrue(output.getOut().contains("语气评分已关闭"));
        server.verify();
    }

    @Test
    void tone配置编解码_保留角度且坏JSON回落AI() {
        String json = DimensionMetricConfig.toJson(DimensionMetricConfig.METRIC_TONE, null, "热情");
        DimensionMetricConfig parsed = DimensionMetricConfig.parse(json);

        assertEquals(DimensionMetricConfig.METRIC_TONE, parsed.metric());
        assertEquals("热情", parsed.stringParam("aspect", ""));
        assertNull(DimensionMetricConfig.parse("{bad json"));
    }

    private void expectSuccess(int score) throws Exception {
        server.expect(requestTo(URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("qwen3.8-omni-flash"))
                .andExpect(jsonPath("$.temperature").value(0.1))
                .andExpect(jsonPath("$.messages[0].content[0].text").value(
                        org.hamcrest.Matchers.containsString("热情")))
                .andExpect(jsonPath("$.messages[0].content[1].type").value("input_audio"))
                .andExpect(jsonPath("$.messages[0].content[1].input_audio.data").value(
                        startsWith("data:audio/wav;base64,")))
                .andRespond(withSuccess(responseBody(score), MediaType.APPLICATION_JSON));
    }

    private String responseBody(int score) throws Exception {
        String content = "{\"score\":" + score + ",\"evidence\":\"evidence-" + score + "\"}";
        return MAPPER.writeValueAsString(Map.of(
                "choices", List.of(Map.of(
                        "message", Map.of("content", content)))));
    }

    private DimensionMetricConfig toneMetric() {
        return DimensionMetricConfig.parse(
                "{\"metric\":\"tone\",\"params\":{\"aspect\":\"热情\"}}");
    }

    private ToneScoringService.ToneRoundInput round(Path path, int charCount) {
        return new ToneScoringService.ToneRoundInput(1L, path, "你好，世界", charCount);
    }
}
