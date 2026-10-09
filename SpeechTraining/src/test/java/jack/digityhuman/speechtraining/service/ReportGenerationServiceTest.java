package jack.digityhuman.speechtraining.service;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.speechtraining.common.DimensionMetricConfig;
import jack.digityhuman.speechtraining.dify.DifyReportScoringService;
import jack.digityhuman.speechtraining.entity.ScoringDimension;
import jack.digityhuman.speechtraining.entity.TrainingReportDimension;
import jack.digityhuman.speechtraining.entity.TrainingRoundAcoustic;
import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import jack.digityhuman.speechtraining.mapper.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ReportGenerationServiceTest {

    @Mock
    private TrainingReportMapper reportMapper;
    @Mock
    private TrainingReportDimensionMapper reportDimensionMapper;
    @Mock
    private TrainingSessionMapper sessionMapper;
    @Mock
    private TrainingSessionMessageMapper messageMapper;
    @Mock
    private TrainingNodeMapper nodeMapper;
    @Mock
    private ScoringDimensionMapper scoringDimensionMapper;
    @Mock
    private VirtualTrainerMapper virtualTrainerMapper;
    @Mock
    private ForbiddenWordChecker forbiddenWordChecker;
    @Mock
    private DifyReportScoringService difyReportScoringService;
    @Mock
    private TrainingRoundAcousticMapper acousticMapper;
    @Mock
    private ToneScoringService toneScoringService;

    private ReportGenerationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ReportGenerationServiceImpl(reportMapper, reportDimensionMapper, sessionMapper,
                messageMapper, nodeMapper, scoringDimensionMapper, virtualTrainerMapper,
                forbiddenWordChecker, difyReportScoringService, new SpeechSpeedCalculator(), acousticMapper,
                toneScoringService);
    }

    @Test
    void 语音轮优先speechMs_20字7秒为171且每会话只查一次() {
        TrainingSessionMessage question = message(1L, 1, "请介绍一下自己", null, null, null);
        TrainingSessionMessage answer1 = message(2L, 2, "一二三四五六七八九十一二三四五六七八九十", 2, 10_000, 1);
        TrainingSessionMessage answer2 = message(3L, 2, "一二三四五六七八九十一二三四五六七八九十", 2, 8_000, 2);
        when(acousticMapper.selectList(any())).thenReturn(List.of(
                acoustic(2L, 7_000, 1, 2_300),
                acoustic(3L, 6_000, 0, 0)));

        List<Map<String, Object>> rounds = service.buildRounds(9L,
                List.of(question, answer1, answer2));

        assertEquals(171, rounds.get(0).get("speech_speed"));
        assertEquals(200, rounds.get(1).get("speech_speed"));
        assertEquals(1, rounds.get(0).get("pause_count"));
        assertEquals(2_300, rounds.get(0).get("pause_total_ms"));
        verify(acousticMapper, times(1)).selectList(any());
    }

    @Test
    void speechMs缺失或非正_回落audioDurationMs且每报告只告警一次(CapturedOutput output) {
        TrainingSessionMessage question = message(1L, 1, "请介绍一下自己", null, null, null);
        TrainingSessionMessage answer1 = message(2L, 2, "一二三四五六七八九十一二三四五六七八九十", 2, 10_000, 1);
        TrainingSessionMessage answer2 = message(3L, 2, "一二三四五六七八九十一二三四五六七八九十", 2, 8_000, 2);
        when(acousticMapper.selectList(any())).thenReturn(List.of(
                acoustic(2L, null, null, null),
                acoustic(3L, 0, null, null)));

        List<Map<String, Object>> rounds = service.buildRounds(9L,
                List.of(question, answer1, answer2));

        assertEquals(120, rounds.get(0).get("speech_speed"));
        assertEquals(150, rounds.get(1).get("speech_speed"));
        assertEquals(1, occurrences(output.getOut(), "语速回落 audioDurationMs"));
    }

    @Test
    void 混合维度_含语音轮时注入声学参考() {
        ScoringDimension dim = dimension("表达流利", 3, null);
        List<Map<String, Object>> rounds = List.of(
                Map.of("seq", 1, "speech_speed", 192, "pause_count", 1, "pause_total_ms", 2_300),
                Map.of("seq", 2, "speech_speed", 205));

        List<ReportGenerationServiceImpl.DimensionPlan> plans =
                service.planDimensions(List.of(dim), List.of(192, 205), List.of(),
                        new ForbiddenWordChecker.ForbiddenResult(0, Map.of()));
        String json = service.toDimensionsJson(plans, rounds);

        assertTrue(json.contains("语音表现参考"));
        assertTrue(json.contains("第1轮 语速192字/分、停顿1次共2.3秒"));
        assertTrue(json.contains("第2轮 语速205字/分、无停顿记录"));
    }

    @Test
    void 纯文字会话_混合维度不注入声学参考() {
        ScoringDimension dim = dimension("表达流利", 3, null);
        List<ReportGenerationServiceImpl.DimensionPlan> plans =
                service.planDimensions(List.of(dim), List.of(), List.of(),
                        new ForbiddenWordChecker.ForbiddenResult(0, Map.of()));

        String json = service.toDimensionsJson(plans,
                List.of(Map.of("seq", 1, "student_answer", "纯文字回答")));

        assertFalse(json.contains("语音表现参考"));
    }

    @Test
    void 语音依据维度_无语音时不计分且提示权重已计入总分() {
        ScoringDimension dim = dimension("语速适中", 2, null);

        List<ReportGenerationServiceImpl.DimensionPlan> plans =
                service.planDimensions(List.of(dim), List.of(), List.of(),
                        new ForbiddenWordChecker.ForbiddenResult(0, Map.of()));

        assertFalse(plans.get(0).participates());
        assertTrue(plans.get(0).excludeReason().contains("权重已计入总分"));
        assertNull(plans.get(0).measured());
    }

    @Test
    void 定分维度_即使有语音轮也不注入声学参考() {
        ScoringDimension dim = dimension("表达流利", 3,
                "{\"metric\":\"speech_speed\",\"params\":{\"bestMin\":160,\"bestMax\":240}}");
        List<ReportGenerationServiceImpl.DimensionPlan> plans =
                service.planDimensions(List.of(dim), List.of(150), List.of(),
                        new ForbiddenWordChecker.ForbiddenResult(0, Map.of()));

        String json = service.toDimensionsJson(plans,
                List.of(Map.of("seq", 1, "speech_speed", 150, "pause_count", 1, "pause_total_ms", 2_000)));

        assertTrue(json.contains("后端已判定"));
        assertFalse(json.contains("语音表现参考"));
        assertEquals(95, plans.get(0).measured().score());
    }

    @Test
    void 违禁词指标_按维度deductPerHit算分() {
        ForbiddenWordChecker.ForbiddenResult result =
                new ForbiddenWordChecker.ForbiddenResult(2, Map.of("不清楚", 1, "再说", 1));
        DimensionMetricConfig metric = DimensionMetricConfig.parse(
                "{\"metric\":\"forbidden_words\",\"params\":{\"deductPerHit\":35}}");

        MetricOutcome outcome =
                service.measure(metric, List.of(), List.of(), result);

        assertEquals(30, outcome.score());
        assertTrue(outcome.comment().contains("每次扣 35 分"));
    }

    @Test
    void tone轮次_仅取通过且未撤回的语音答() {
        TrainingSessionMessage question = message(1L, 1, "请介绍一下自己", null, null, null);
        TrainingSessionMessage voice = message(2L, 2, "你好，世界", 2, 10_000, 1);
        voice.setAudioUrl("/audio/asr/2.wav");
        TrainingSessionMessage text = message(3L, 2, "文字回答", 1, null, 2);
        TrainingSessionMessage withdrawn = message(4L, 2, "撤回回答", 2, 10_000, 3);
        withdrawn.setWithdrawn(1);
        withdrawn.setAudioUrl("/audio/asr/4.wav");
        when(toneScoringService.resolveAudioFile("/audio/asr/2.wav"))
                .thenReturn(Path.of("audio", "asr", "2.wav"));

        List<ToneScoringService.ToneRoundInput> rounds =
                service.buildToneRounds(List.of(question, voice, text, withdrawn));

        assertEquals(1, rounds.size());
        assertEquals(2L, rounds.get(0).messageId());
        assertEquals(4, rounds.get(0).charCount());
        assertEquals(Path.of("audio", "asr", "2.wav"), rounds.get(0).audioFile());
        verify(toneScoringService, never()).resolveAudioFile("/audio/asr/4.wav");
    }

    @Test
    void tone指标_模型评分成功时由后端定分并注入rule() {
        ScoringDimension dim = dimension("语气热情", 2,
                "{\"metric\":\"tone\",\"params\":{\"aspect\":\"热情\"}}");
        List<ToneScoringService.ToneRoundInput> toneRounds = List.of(
                new ToneScoringService.ToneRoundInput(2L, Path.of("audio", "asr", "2.wav"), "你好", 2));
        when(toneScoringService.measureTone(any(), any())).thenReturn(
                new MetricOutcome(88, "语音模型从热情角度评分 88 分",
                        "本轮从热情角度的语音表现评分为 88 分，主要依据：结尾上扬。",
                        List.of(), List.of()));

        List<ReportGenerationServiceImpl.DimensionPlan> plans =
                service.planDimensions(List.of(dim), List.of(), toneRounds,
                        new ForbiddenWordChecker.ForbiddenResult(0, Map.of()));
        String json = service.toDimensionsJson(plans, List.of(Map.of("seq", 1, "student_answer", "你好")));

        assertTrue(plans.get(0).participates());
        assertEquals(88, plans.get(0).measured().score());
        assertTrue(json.contains("后端已判定"));
        assertTrue(json.contains("语音模型从热情角度评分 88 分"));
    }

    @Test
    void tone指标_模型失败时明确不参与评分而非回落AI() {
        ScoringDimension dim = dimension("语气热情", 2,
                "{\"metric\":\"tone\",\"params\":{\"aspect\":\"热情\"}}");
        List<ToneScoringService.ToneRoundInput> toneRounds = List.of(
                new ToneScoringService.ToneRoundInput(2L, Path.of("audio", "asr", "2.wav"), "你好", 2));
        when(toneScoringService.measureTone(any(), any())).thenReturn(null);

        List<ReportGenerationServiceImpl.DimensionPlan> plans =
                service.planDimensions(List.of(dim), List.of(), toneRounds,
                        new ForbiddenWordChecker.ForbiddenResult(0, Map.of()));

        assertFalse(plans.get(0).participates());
        assertTrue(plans.get(0).excludeReason().contains("未参与本次评分"));
        assertNull(plans.get(0).measured());
    }

    @Test
    void 总分_未参与维度权重仍计入_其余满分时90() {
        List<TrainingReportDimension> rows = List.of(
                row(40, new BigDecimal("100")),
                row(10, new BigDecimal("100")),
                row(10, new BigDecimal("100")),
                row(10, new BigDecimal("100")),
                row(10, new BigDecimal("100")),
                row(10, new BigDecimal("100")),
                row(10, null));

        assertEquals(new BigDecimal("90.0"), service.calculateTotalScore(rows));
    }

    @Test
    void 总分_全部维度都无分时仍按报告失败处理() {
        List<TrainingReportDimension> rows = List.of(row(40, null), row(10, null));

        assertThrows(BizException.class, () -> service.calculateTotalScore(rows));
    }

    @Test
    void 总分_全部权重为零时按已评分维度简单平均() {
        List<TrainingReportDimension> rows = List.of(
                row(0, new BigDecimal("80")),
                row(0, new BigDecimal("100")));

        assertEquals(new BigDecimal("90.0"), service.calculateTotalScore(rows));
    }

    @Test
    void 总分_权重总和不为100时仍按权重归一() {
        List<TrainingReportDimension> rows = List.of(
                row(15, new BigDecimal("100")),
                row(15, new BigDecimal("70")),
                row(30, new BigDecimal("90")));

        assertEquals(new BigDecimal("87.5"), service.calculateTotalScore(rows));
    }

    private TrainingSessionMessage message(Long id, int role, String content,
                                           Integer inputMode, Integer audioDurationMs, Integer roundNo) {
        TrainingSessionMessage message = new TrainingSessionMessage();
        message.setId(id);
        message.setSessionId(9L);
        message.setRole(role);
        message.setContent(content);
        message.setPassed(role == 2 ? 1 : null);
        message.setWithdrawn(0);
        message.setInputMode(inputMode);
        message.setAudioDurationMs(audioDurationMs);
        message.setRoundNo(roundNo);
        return message;
    }

    private TrainingRoundAcoustic acoustic(Long messageId, Integer speechMs,
                                           Integer pauseCount, Integer pauseTotalMs) {
        TrainingRoundAcoustic acoustic = new TrainingRoundAcoustic();
        acoustic.setSessionId(9L);
        acoustic.setMessageId(messageId);
        acoustic.setSpeechMs(speechMs);
        acoustic.setPauseCount(pauseCount);
        acoustic.setPauseTotalMs(pauseTotalMs);
        return acoustic;
    }

    private ScoringDimension dimension(String name, int evidenceType, String metricConfig) {
        ScoringDimension dim = new ScoringDimension();
        dim.setDimensionName(name);
        dim.setWeight(10);
        dim.setScoringRule("测试规则");
        dim.setEvidenceType(evidenceType);
        dim.setMetricConfig(metricConfig);
        return dim;
    }

    private TrainingReportDimension row(int weight, BigDecimal score) {
        TrainingReportDimension row = new TrainingReportDimension();
        row.setWeight(weight);
        row.setScore(score);
        return row;
    }

    private int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    void hit_points随轮次进入payload_解析失败按空处理() {
        // v1.2：轮次评分器判定的命中要点作为事实传给报告评分智能体（不再由报告 AI 重判）
        TrainingSessionMessage question = message(1L, 1, "请介绍一下自己", null, null, null);
        TrainingSessionMessage answer1 = message(2L, 2, "我是张伟，负责快反王2销售", null, null, 1);
        answer1.setHitPoints("[\"自我介绍\",\"产品熟悉度\"]");
        TrainingSessionMessage answer2 = message(3L, 2, "我们设备跳针率下降97%", null, null, 2);
        answer2.setHitPoints("not-a-json"); // 解析失败 → 空列表

        List<Map<String, Object>> rounds = service.buildRounds(9L,
                List.of(question, answer1, answer2));

        assertEquals(List.of("自我介绍", "产品熟悉度"), rounds.get(0).get("hit_points"));
        assertEquals(List.of(), rounds.get(1).get("hit_points"));
    }
}
