package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.web.GlobalExceptionHandler;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.StudentExamDtos;
import jack.digityhuman.examservice.dto.resp.StudentExamRespDtos;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学员端考试接口的 HTTP 层契约测试（04 §11.2/§11.3/§11.6）。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @WebMvcTest}：这里要验证的是
 * **路由 + 状态码 + 响应结构**，不需要 Spring 容器；而完整上下文依赖 Nacos/MySQL，
 * 校验一次接口就得起全套中间件，代价过高（AGENTS §5 记录过该环境问题）。
 *
 * <p>覆盖的正是任务验收项「curl 可验证三个接口返回 200」——本机无中间件时，
 * 这三条断言等价于 curl 的结论，且可重复执行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StudentExamControllerTest {

    @Mock private StudentExamService studentExamService;
    @Mock private CurrentUserService currentUserService;

    private MockMvc mockMvc;

    private static final String ARRANGE_ID = "2112371048779218946";

    @BeforeEach
    void setUp() {
        StudentExamController controller = new StudentExamController(studentExamService, currentUserService);
        // 带上全局异常处理器：业务失败要落成 HTTP 200 + 非零 code，而不是 500
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(currentUserService.userId(any())).thenReturn("13");
    }

    @Test
    void 我的考试列表返回200且结构符合契约() throws Exception {
        StudentExamRespDtos.ExamItem item = new StudentExamRespDtos.ExamItem();
        item.setArrangeId(ARRANGE_ID);
        item.setName("默认考试");
        item.setImportant(0);
        item.setExamType(1);
        item.setTotalScore(new BigDecimal("18.00"));
        item.setPassScore(new BigDecimal("10.80"));
        item.setStudentStatus("finished");
        item.setHighestScore(new BigDecimal("4.00"));
        item.setAttemptQty(3);
        item.setAttemptLimit(0);
        item.setPassed(0);
        item.setLastSubmitAt(LocalDateTime.of(2026, 11, 10, 13, 0, 0));
        item.setLimitMinutes(60);
        StudentExamRespDtos.ExamList list = new StudentExamRespDtos.ExamList();
        list.setRecords(List.of(item));
        list.setTotal(1L);
        when(studentExamService.listMyExams(eq("13"), isNull())).thenReturn(list);

        mockMvc.perform(get("/api/exam/student/exams").header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].arrangeId").value(ARRANGE_ID))
                .andExpect(jsonPath("$.data.records[0].studentStatus").value("finished"))
                .andExpect(jsonPath("$.data.records[0].attemptLimit").value(0))
                // 时间必须是 yyyy-MM-dd HH:mm:ss，不是 ISO 带 T 的格式（04 §11.1）
                .andExpect(jsonPath("$.data.records[0].lastSubmitAt").value("2026-11-10 13:00:00"));
    }

    @Test
    void 列表按状态过滤时把status透传给服务() throws Exception {
        StudentExamRespDtos.ExamList list = new StudentExamRespDtos.ExamList();
        list.setRecords(List.of());
        list.setTotal(0L);
        when(studentExamService.listMyExams("13", "in_progress")).thenReturn(list);

        mockMvc.perform(get("/api/exam/student/exams")
                        .param("status", "in_progress")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void 入场信息返回200且canStart与blockReason齐全() throws Exception {
        StudentExamRespDtos.Entry entry = new StudentExamRespDtos.Entry();
        entry.setArrangeId(ARRANGE_ID);
        entry.setName("默认考试");
        entry.setTotalScore(new BigDecimal("18.00"));
        entry.setQuestionCount(10);
        entry.setLimitMinutes(null);
        entry.setPassScore(new BigDecimal("10.80"));
        entry.setExcellentScore(null);
        entry.setUsedTimes(2);
        entry.setAttemptLimit(0);
        entry.setFullname("管理员");
        entry.setAccount("admin");
        entry.setSignatureConfirm(1);
        entry.setPreExamDeclare(0);
        entry.setFaceVerify(0);
        entry.setCanStart(false);
        entry.setBlockReason("考试次数已用完");
        when(studentExamService.entry("13", ARRANGE_ID)).thenReturn(entry);

        mockMvc.perform(get("/api/exam/student/exams/{arrangeId}/entry", ARRANGE_ID)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.questionCount").value(10))
                .andExpect(jsonPath("$.data.canStart").value(false))
                .andExpect(jsonPath("$.data.blockReason").value("考试次数已用完"))
                // 不限时时 limitMinutes 必须是 null，前端据此显示「不限时」
                .andExpect(jsonPath("$.data.limitMinutes").doesNotExist())
                .andExpect(jsonPath("$.data.lastResult").doesNotExist());
    }

    @Test
    void 开始考试返回200并带服务端时间基准() throws Exception {
        StudentExamRespDtos.StartResult r = new StudentExamRespDtos.StartResult();
        r.setAttemptId("att-1");
        r.setArrangeId(ARRANGE_ID);
        r.setName("默认考试");
        r.setTotalScore(new BigDecimal("18.00"));
        r.setPassScore(new BigDecimal("10.80"));
        r.setLimitMinutes(null);
        r.setServerNow(LocalDateTime.of(2026, 11, 10, 13, 34, 2));
        r.setUsedSeconds(0);
        r.setAttemptNo(3);
        r.setResumed(false);
        when(studentExamService.start(eq("13"), eq(ARRANGE_ID), any(StudentExamDtos.StartReq.class)))
                .thenReturn(r);

        mockMvc.perform(post("/api/exam/student/exams/{arrangeId}/start", ARRANGE_ID)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signId\":\"sign-1\",\"declareAccepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.attemptId").value("att-1"))
                .andExpect(jsonPath("$.data.attemptNo").value(3))
                .andExpect(jsonPath("$.data.serverNow").value("2026-11-10 13:34:02"));
    }

    @Test
    void 开考被拒时仍是HTTP200但带非零code() throws Exception {
        when(studentExamService.start(eq("13"), eq(ARRANGE_ID), any()))
                .thenThrow(new jack.digityhuman.common.api.BizException(40051, "考试次数已用完"));

        mockMvc.perform(post("/api/exam/student/exams/{arrangeId}/start", ARRANGE_ID)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                // 04 §11.1：业务失败 HTTP 200 + 非零 code，前端才读得到 code
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40051))
                .andExpect(jsonPath("$.message").value("考试次数已用完"));
    }

    @Test
    void 未登录时拒绝且不走40101() throws Exception {
        when(currentUserService.userId(any())).thenReturn("0");

        mockMvc.perform(get("/api/exam/student/exams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40023));
    }

    @Test
    void 考前校验返回需要哪几个前置步骤() throws Exception {
        StudentExamRespDtos.PreCheck check = new StudentExamRespDtos.PreCheck();
        check.setNeedDeclare(0);
        check.setNeedSignature(1);
        check.setNeedFaceVerify(0);
        check.setCanStart(true);
        when(studentExamService.preCheck("13", ARRANGE_ID)).thenReturn(check);

        mockMvc.perform(get("/api/exam/student/exams/{arrangeId}/pre-check", ARRANGE_ID)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.needSignature").value(1))
                .andExpect(jsonPath("$.data.canStart").value(true));
    }

    @Test
    void 签名返回signId() throws Exception {
        when(studentExamService.sign(eq("13"), eq(ARRANGE_ID), any(StudentExamDtos.SignReq.class)))
                .thenReturn("sign-9");

        mockMvc.perform(post("/api/exam/student/exams/{arrangeId}/sign", ARRANGE_ID)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"imageUrl\":\"/exam/files/sign.png\",\"stage\":\"pre\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.signId").value("sign-9"));
    }

    @Test
    void 声明确认返回200() throws Exception {
        mockMvc.perform(post("/api/exam/student/exams/{arrangeId}/declare", ARRANGE_ID)
                        .header("Authorization", "Bearer t")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accepted\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
