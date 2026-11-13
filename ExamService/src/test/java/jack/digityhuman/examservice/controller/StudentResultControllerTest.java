package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.web.GlobalExceptionHandler;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.resp.StudentResultRespDtos;
import jack.digityhuman.examservice.service.StudentResultService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 结果页与答卷详情接口的 HTTP 层契约测试（04 §11.12 / §11.13）。
 *
 * <p>重点断言两件事：
 * <ol>
 *   <li>`graded=false` 时 `score`/`passed`/`rankNo` <b>字段缺席</b>（不是 0）；</li>
 *   <li>不可查看答卷详情时是 `40055` + 可展示原因（HTTP 仍 200）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentResultControllerTest {

    @Mock private StudentResultService studentResultService;
    @Mock private CurrentUserService currentUserService;

    private MockMvc mockMvc;

    private static final String ATTEMPT = "att-1";

    @BeforeEach
    void setUp() {
        StudentResultController controller = new StudentResultController(studentResultService, currentUserService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(currentUserService.userId(any())).thenReturn("13");
    }

    @Test
    void 批阅中时结果页返回200且总分通过排名字段缺席() throws Exception {
        StudentResultRespDtos.Result r = new StudentResultRespDtos.Result();
        r.setAttemptId(ATTEMPT);
        r.setArrangeId("arr-1");
        r.setName("默认考试");
        r.setStatus(2);
        r.setGraded(false);
        // 关键：未出分时这三项为 null → Jackson 不下发
        r.setScore(null);
        r.setPassed(null);
        r.setRankNo(null);
        r.setTotalScore(new BigDecimal("18.00"));
        r.setPassScore(new BigDecimal("10.80"));
        r.setObjectiveScore(new BigDecimal("4.00"));
        r.setObjectiveFullScore(new BigDecimal("9.00"));
        r.setSubjectivePending(new BigDecimal("9.00"));
        r.setAnswerDetailAvailable(true);
        r.setPointScores(List.of());
        r.setTypeStats(List.of());
        r.setHistory(List.of());
        when(studentResultService.result("13", ATTEMPT)).thenReturn(r);

        mockMvc.perform(get("/api/exam/student/attempts/{id}/result", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.graded").value(false))
                .andExpect(jsonPath("$.data.status").value(2))
                // 未出分：不能出现 score/passed/rankNo
                .andExpect(jsonPath("$.data.score").doesNotExist())
                .andExpect(jsonPath("$.data.passed").doesNotExist())
                .andExpect(jsonPath("$.data.rankNo").doesNotExist())
                // 但要先给客观题分（DER-03）
                .andExpect(jsonPath("$.data.objectiveScore").value(4.00))
                .andExpect(jsonPath("$.data.subjectivePending").value(9.00));
    }

    @Test
    void 已出分时返回score与passed与rankNo() throws Exception {
        StudentResultRespDtos.Result r = new StudentResultRespDtos.Result();
        r.setAttemptId(ATTEMPT);
        r.setStatus(3);
        r.setGraded(true);
        r.setScore(new BigDecimal("15.00"));
        r.setPassed(1);
        r.setRankNo(2);
        r.setTotalScore(new BigDecimal("18.00"));
        r.setPassScore(new BigDecimal("10.80"));
        r.setPointScores(List.of());
        r.setTypeStats(List.of());
        r.setHistory(List.of());
        when(studentResultService.result("13", ATTEMPT)).thenReturn(r);

        mockMvc.perform(get("/api/exam/student/attempts/{id}/result", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.graded").value(true))
                .andExpect(jsonPath("$.data.score").value(15.00))
                .andExpect(jsonPath("$.data.passed").value(1))
                .andExpect(jsonPath("$.data.rankNo").value(2));
    }

    @Test
    void 答卷详情不可查看时返回40055并带原因() throws Exception {
        when(studentResultService.paperDetail(eq("13"), eq(ATTEMPT), isNull()))
                .thenThrow(new BizException(40055, "考试结束后允许查看"));

        mockMvc.perform(get("/api/exam/student/attempts/{id}/paper-detail", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40055))
                .andExpect(jsonPath("$.message").value("考试结束后允许查看"));
    }

    @Test
    void 只看错题参数透传给服务() throws Exception {
        StudentResultRespDtos.PaperDetail detail = new StudentResultRespDtos.PaperDetail();
        detail.setAttemptId(ATTEMPT);
        detail.setName("默认考试");
        detail.setSections(List.of());
        when(studentResultService.paperDetail("13", ATTEMPT, 1)).thenReturn(detail);

        mockMvc.perform(get("/api/exam/student/attempts/{id}/paper-detail", ATTEMPT)
                        .param("wrongOnly", "1")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                // 只看错题且全对 → sections 为空数组（真过滤）
                .andExpect(jsonPath("$.data.sections").isArray())
                .andExpect(jsonPath("$.data.sections").isEmpty());
    }

    @Test
    void 未登录时不走40101而是40023() throws Exception {
        when(currentUserService.userId(any())).thenReturn("0");

        mockMvc.perform(get("/api/exam/student/attempts/{id}/result", ATTEMPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40023));
    }
}
