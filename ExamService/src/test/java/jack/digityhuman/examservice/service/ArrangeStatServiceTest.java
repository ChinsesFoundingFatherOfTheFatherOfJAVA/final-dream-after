package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.resp.ArrangeStatRespDtos;
import jack.digityhuman.examservice.entity.ExamArrange;
import jack.digityhuman.examservice.mapper.*;
import jack.digityhuman.examservice.service.impl.ArrangeStatServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 统计空数据兜底：没有试卷/没有答卷时不能抛异常，比率一律 0。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArrangeStatServiceTest {

    @Mock private ExamArrangeMapper arrangeMapper;
    @Mock private ExamPaperMapper paperMapper;
    @Mock private ExamPaperQuestionMapper paperQuestionMapper;
    @Mock private ExamQuestionMapper questionMapper;
    @Mock private ExamQuestionItemMapper itemMapper;
    @Mock private ExamQuestionPointMapper questionPointMapper;
    @Mock private ExamPointMapper pointMapper;
    @Mock private ExamAttemptMapper attemptMapper;
    @Mock private ExamAttemptAnswerMapper answerMapper;
    @Mock private ExamArrangeStudentMapper studentMapper;
    @Mock private ExamArrangeScoreStatMapper scoreStatMapper;
    @InjectMocks private ArrangeStatServiceImpl service;

    private ExamArrange arrange() {
        ExamArrange a = new ExamArrange();
        a.setId("aid");
        a.setName("空数据考试");
        a.setParticipantQty(0);
        a.setSubmittedQty(0);
        a.setPassedQty(0);
        return a;
    }

    @Test
    void 无答卷时正确率返回空维度而不是抛错() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(paperQuestionMapper.selectList(any())).thenReturn(List.of());

        ArrangeStatRespDtos.CorrectRateResp resp = service.correctRate("aid", "type");

        assertThat(resp.getAccuracyRate()).containsKeys("quesType", "difficulty", "point");
        assertThat((List<?>) resp.getAccuracyRate().get("quesType")).isEmpty();
    }

    @Test
    void 空数据指标卡各比率均为0() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(scoreStatMapper.selectById("aid")).thenReturn(null);

        ArrangeStatRespDtos.OverviewResp resp = service.overview("aid");
        Map<String, Object> base = resp.getBaseInfo();
        assertThat(base.get("totalQty")).isEqualTo(0);
        assertThat(base.get("answerQty")).isEqualTo(0);
        assertThat((BigDecimal) base.get("passRate")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) base.get("excellentRate")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void 没有人交卷时分值统计返回0而不是除零异常() {
        when(arrangeMapper.selectById("aid")).thenReturn(arrange());
        when(studentMapper.selectList(any())).thenReturn(List.of());

        ArrangeStatRespDtos.ScoreResp resp = service.score("aid");
        assertThat((BigDecimal) resp.getScoreInfo().get("averageScore")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat((BigDecimal) resp.getScoreInfo().get("topScore".equals("") ? "highestScore" : "highestScore"))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }
}