package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.web.GlobalExceptionHandler;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.StudentAnswerDtos;
import jack.digityhuman.examservice.dto.resp.StudentAnswerRespDtos;
import jack.digityhuman.examservice.service.StudentAnswerService;
import jack.digityhuman.examservice.service.StudentExamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学员端答题接口的 HTTP 层契约测试（04 §11.7–§11.11）。
 *
 * <p>验的是路由、状态码与响应形状；判分与幂等语义由 `StudentAnswerServiceTest` 覆盖。
 * 业务失败必须是 HTTP 200 + 非零 code（04 §11.1），这里逐条断言。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentAnswerControllerTest {

    @Mock private StudentAnswerService studentAnswerService;
    @Mock private StudentExamService studentExamService;
    @Mock private CurrentUserService currentUserService;

    private MockMvc mockMvc;

    private static final String ATTEMPT = "att-1";

    @BeforeEach
    void setUp() {
        StudentAnswerController controller = new StudentAnswerController(
                studentAnswerService, studentExamService, currentUserService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(currentUserService.userId(any())).thenReturn("13");
    }

    @Test
    void 取卷返回200且不含答案字段() throws Exception {
        StudentAnswerRespDtos.Option opt = new StudentAnswerRespDtos.Option();
        opt.setItemId("opt-a");
        opt.setContent("2");
        opt.setOrderIndex(1);
        StudentAnswerRespDtos.AnswerDraft draft = new StudentAnswerRespDtos.AnswerDraft();
        draft.setAnswer(List.of("opt-a"));
        draft.setAttach(List.of());
        draft.setMarked(0);
        StudentAnswerRespDtos.Card card = new StudentAnswerRespDtos.Card();
        card.setQuesId("q-1");
        card.setType(0);
        card.setScore(new BigDecimal("5.00"));
        card.setContent("<p>1+1=?</p>");
        card.setOptions(List.of(opt));
        card.setAnswer(draft);
        StudentAnswerRespDtos.Section section = new StudentAnswerRespDtos.Section();
        section.setSectionIndex(0);
        section.setSectionName("一、单选题");
        section.setCards(List.of(card));
        StudentAnswerRespDtos.Paper paper = new StudentAnswerRespDtos.Paper();
        paper.setAttemptId(ATTEMPT);
        paper.setLayout("single");
        paper.setSections(List.of(section));
        paper.setSubmitted(false);
        when(studentAnswerService.paper("13", ATTEMPT)).thenReturn(paper);

        mockMvc.perform(get("/api/exam/student/attempts/{id}/paper", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.attemptId").value(ATTEMPT))
                .andExpect(jsonPath("$.data.layout").value("single"))
                .andExpect(jsonPath("$.data.sections[0].cards[0].quesId").value("q-1"))
                .andExpect(jsonPath("$.data.sections[0].cards[0].options[0].itemId").value("opt-a"))
                // 答题态绝不能带标准答案/解析（契约 §11.7 明文）
                .andExpect(jsonPath("$.data.sections[0].cards[0].answerContent").doesNotExist())
                .andExpect(jsonPath("$.data.sections[0].cards[0].judgeAnswer").doesNotExist())
                .andExpect(jsonPath("$.data.sections[0].cards[0].explainText").doesNotExist())
                // 不限时
                .andExpect(jsonPath("$.data.deadlineAt").doesNotExist());
    }

    @Test
    void 保存答案返回saved条数() throws Exception {
        when(studentAnswerService.saveAnswers(eq("13"), eq(ATTEMPT), any(StudentAnswerDtos.SaveAnswersReq.class)))
                .thenReturn(2);

        mockMvc.perform(post("/api/exam/student/attempts/{id}/answers", ATTEMPT)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[{\"quesId\":\"q-1\",\"answer\":[\"opt-a\"],\"attach\":[]},"
                                + "{\"quesId\":\"q-2\",\"answer\":[\"text\"],\"attach\":[]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.saved").value(2));
    }

    @Test
    void 已交卷保存答案返回40053() throws Exception {
        when(studentAnswerService.saveAnswers(eq("13"), eq(ATTEMPT), any()))
                .thenThrow(new BizException(40053, "答卷已提交，不能再修改答案"));

        mockMvc.perform(post("/api/exam/student/attempts/{id}/answers", ATTEMPT)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40053));
    }

    @Test
    void 标记返回marked() throws Exception {
        when(studentAnswerService.flag(eq("13"), eq(ATTEMPT), any(StudentAnswerDtos.FlagReq.class))).thenReturn(1);

        mockMvc.perform(post("/api/exam/student/attempts/{id}/flag", ATTEMPT)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quesId\":\"q-1\",\"marked\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.marked").value(1));
    }

    @Test
    void 心跳返回serverNow与remainingSeconds() throws Exception {
        StudentAnswerRespDtos.TimeState state = new StudentAnswerRespDtos.TimeState();
        state.setUsedSeconds(120);
        state.setRemainingSeconds(3480);
        state.setExitCount(0);
        state.setForceSubmit(false);
        when(studentAnswerService.timeState("13", ATTEMPT)).thenReturn(state);

        mockMvc.perform(put("/api/exam/student/attempts/{id}/time", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.usedSeconds").value(120))
                .andExpect(jsonPath("$.data.remainingSeconds").value(3480))
                .andExpect(jsonPath("$.data.forceSubmit").value(false));
    }

    @Test
    void 切屏上报返回forceSubmitted标志() throws Exception {
        when(studentAnswerService.reportAntiCheat(eq("13"), eq(ATTEMPT), any())).thenReturn(true);

        mockMvc.perform(post("/api/exam/student/attempts/{id}/anti-cheat", ATTEMPT)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventType\":\"screen.hidden\",\"clientEventId\":\"c1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.forceSubmitted").value(true));
    }

    @Test
    void 交卷返回客观题分与待批阅分() throws Exception {
        StudentAnswerRespDtos.SubmitResult r = new StudentAnswerRespDtos.SubmitResult();
        r.setAttemptId(ATTEMPT);
        r.setStatus(2);
        r.setObjectiveScore(new BigDecimal("4.00"));
        r.setObjectiveFullScore(new BigDecimal("9.00"));
        r.setSubjectivePending(new BigDecimal("9.00"));
        when(studentAnswerService.submit(eq("13"), eq(ATTEMPT), any(StudentAnswerDtos.SubmitReq.class)))
                .thenReturn(r);

        mockMvc.perform(put("/api/exam/student/attempts/{id}/submit", ATTEMPT)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"usedSeconds\":265,\"submitType\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(2))
                .andExpect(jsonPath("$.data.objectiveScore").value(4.00))
                .andExpect(jsonPath("$.data.objectiveFullScore").value(9.00))
                .andExpect(jsonPath("$.data.subjectivePending").value(9.00));
    }

    @Test
    void 交卷被前置校验拦住时带原文案() throws Exception {
        when(studentAnswerService.submit(eq("13"), eq(ATTEMPT), any()))
                .thenThrow(new BizException(40054, "还有 3 道题未做"));

        mockMvc.perform(put("/api/exam/student/attempts/{id}/submit", ATTEMPT)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40054))
                .andExpect(jsonPath("$.message").value("还有 3 道题未做"));
    }

    @Test
    void 未登录时不走40101而是40023() throws Exception {
        when(currentUserService.userId(any())).thenReturn("0");

        mockMvc.perform(get("/api/exam/student/attempts/{id}/paper", ATTEMPT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40023));
    }
}
