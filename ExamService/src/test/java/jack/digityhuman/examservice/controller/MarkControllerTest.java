package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.common.web.GlobalExceptionHandler;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.MarkDtos;
import jack.digityhuman.examservice.dto.resp.MarkRespDtos;
import jack.digityhuman.examservice.service.MarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 批阅接口的 HTTP 层契约测试（04 §11B.1–§11B.5）。
 *
 * <p>重点断言四件事：
 * <ol>
 *   <li>路由与参数名与契约一致（前端 `api/studentExam.ts` 已按契约写死）；</li>
 *   <li>时间格式为 `yyyy-MM-dd HH:mm:ss`（列表页直接显示字符串，不做二次格式化）；</li>
 *   <li>未出分时 `score` 字段缺席而不是 0；</li>
 *   <li>业务失败仍是 HTTP 200 + 非零 code（本仓库统一约定）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MarkControllerTest {

    @Mock private MarkService markService;
    @Mock private CurrentUserService currentUserService;

    private MockMvc mockMvc;

    private static final String ARRANGE = "arr-1";
    private static final String ATTEMPT = "att-1";

    @BeforeEach
    void setUp() {
        MarkController controller = new MarkController(markService, currentUserService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(currentUserService.userId(any())).thenReturn("13");
    }

    private MarkRespDtos.MarkExamItem examItem() {
        MarkRespDtos.MarkExamItem item = new MarkRespDtos.MarkExamItem();
        item.setArrangeId(ARRANGE);
        item.setName("默认考试");
        item.setArrangeType(1);
        item.setPublishAt(LocalDateTime.of(2026, 11, 10, 9, 0));
        item.setParticipantQty(1);
        item.setSubmittedQty(1);
        item.setPendingQty(1);
        return item;
    }

    @Test
    void 批阅列表返回200且带待批阅计数() throws Exception {
        when(markService.list(eq("13"), any())).thenReturn(PageResult.of(List.of(examItem()), 1));

        mockMvc.perform(get("/api/exam/mark/list")
                        .param("current", "1").param("limit", "10")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].arrangeId").value(ARRANGE))
                .andExpect(jsonPath("$.data.records[0].pendingQty").value(1))
                .andExpect(jsonPath("$.data.records[0].submittedQty").value(1))
                // 发布日期只到天（契约 §11B.1 示例 "2026-11-10"）
                .andExpect(jsonPath("$.data.records[0].publishAt").value("2026-11-10"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void 批阅列表把筛选参数透传给服务() throws Exception {
        when(markService.list(eq("13"), any())).thenReturn(PageResult.of(List.of(), 0));

        mockMvc.perform(get("/api/exam/mark/list")
                        .param("keyword", "默认")
                        .param("arrangeType", "1")
                        .param("startDate", "2026-11-01")
                        .param("endDate", "2026-11-30")
                        .param("current", "2")
                        .param("limit", "20")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk());

        ArgumentCaptor<MarkService.Query> captor = ArgumentCaptor.forClass(MarkService.Query.class);
        org.mockito.Mockito.verify(markService).list(eq("13"), captor.capture());
        MarkService.Query q = captor.getValue();
        assertThat(q.getKeyword()).isEqualTo("默认");
        assertThat(q.getArrangeType()).isEqualTo(1);
        assertThat(q.getStartDate()).isEqualTo("2026-11-01");
        assertThat(q.getEndDate()).isEqualTo("2026-11-30");
        assertThat(q.getCurrent()).isEqualTo(2);
        assertThat(q.getLimit()).isEqualTo(20);
    }

    @Test
    void 答卷列表时间格式为yyyy_MM_dd_HH_mm_ss且未出分时score为null() throws Exception {
        MarkRespDtos.MarkUserItem item = new MarkRespDtos.MarkUserItem();
        item.setAttemptId(ATTEMPT);
        item.setAccount("admin");
        item.setFullname("管理员");
        item.setDeptName("杰克科技股份");
        item.setPositionName("--");
        item.setSubmitAt(LocalDateTime.of(2026, 11, 10, 13, 0, 0));
        item.setUsedSeconds(43);
        item.setMarkStatus(0);
        item.setIdentityStatus(2);
        // 未出分：必须是 null（不给 0，否则批阅人以为学员考砸了）
        item.setScore(null);
        item.setGraderNames(null);
        item.setGradedAt(null);
        when(markService.users(eq("13"), eq(ARRANGE), any())).thenReturn(PageResult.of(List.of(item), 1));

        String body = mockMvc.perform(get("/api/exam/mark/{arrangeId}/users", ARRANGE)
                        .param("markStatus", "0")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].submitAt").value("2026-11-10 13:00:00"))
                .andExpect(jsonPath("$.data.records[0].markStatus").value(0))
                .andExpect(jsonPath("$.data.records[0].identityStatus").value(2))
                .andReturn().getResponse().getContentAsString();

        // 断言原始 JSON 里就是 null 而不是 0：
        // 契约 §11B.2 的示例本身就是 `"graderNames": null, "score": 4.00`，
        // 所以这里是「字段在、值为 null」，不是「字段缺席」。
        // 用 doesNotExist() 断言是错的——JSON null 在 JsonPath 里同样取不到值，
        // 那条断言在 score 被写成 0 时也会通过（第一版就踩了这个坑）。
        assertThat(body).contains("\"score\":null");
        assertThat(body).contains("\"graderNames\":null");
        assertThat(body).doesNotContain("\"score\":0");
    }

    @Test
    void 批阅详情带身份证据与已打分结果() throws Exception {
        MarkRespDtos.PaperDetail detail = new MarkRespDtos.PaperDetail();
        detail.setAttemptId(ATTEMPT);
        detail.setName("默认考试");
        detail.setTotalScore(new BigDecimal("9.00"));
        detail.setScore(null);
        detail.setPassScore(new BigDecimal("10.80"));
        detail.setSubmitAt(LocalDateTime.of(2026, 11, 10, 13, 0, 0));
        detail.setIdentityStatus(2);
        detail.setIdentityEvidence("/exam/files/2026/11/face-x.png");
        detail.setSections(List.of());
        MarkRespDtos.GradeItem g = new MarkRespDtos.GradeItem();
        g.setQuesId("q-1");
        g.setCorrectFlag(2);
        g.setScore(new BigDecimal("3.00"));
        g.setMaxScore(new BigDecimal("3.00"));
        g.setComment("关键词缺失");
        detail.setGrades(List.of(g));
        when(markService.paper("13", ATTEMPT)).thenReturn(detail);

        mockMvc.perform(get("/api/exam/mark/paper/{attemptId}", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.identityStatus").value(2))
                .andExpect(jsonPath("$.data.identityEvidence").value("/exam/files/2026/11/face-x.png"))
                .andExpect(jsonPath("$.data.grades[0].correctFlag").value(2))
                .andExpect(jsonPath("$.data.grades[0].comment").value("关键词缺失"))
                .andExpect(jsonPath("$.data.submitAt").value("2026-11-10 13:00:00"));
    }

    @Test
    void 提交批阅返回200且回传提交后状态() throws Exception {
        MarkRespDtos.SubmitResult result = new MarkRespDtos.SubmitResult();
        result.setAttemptId(ATTEMPT);
        result.setStatus(3);
        result.setScore(new BigDecimal("12.00"));
        result.setPassed(1);
        result.setRankNo(1);
        result.setPendingQty(0);
        when(markService.submit(eq("13"), eq(ATTEMPT), any())).thenReturn(result);

        mockMvc.perform(post("/api/exam/mark/paper/{attemptId}", ATTEMPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"grades":[{"quesId":"q-1","correctFlag":1,"score":2.00,"comment":"回答完整"}],
                                 "overallComment":"总体良好","invalid":false}
                                """)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value(3))
                .andExpect(jsonPath("$.data.score").value(12.00))
                .andExpect(jsonPath("$.data.passed").value(1))
                .andExpect(jsonPath("$.data.pendingQty").value(0));

        ArgumentCaptor<MarkDtos.SubmitReq> captor = ArgumentCaptor.forClass(MarkDtos.SubmitReq.class);
        org.mockito.Mockito.verify(markService).submit(eq("13"), eq(ATTEMPT), captor.capture());
        MarkDtos.SubmitReq req = captor.getValue();
        assertThat(req.getGrades()).hasSize(1);
        assertThat(req.getGrades().get(0).getQuesId()).isEqualTo("q-1");
        assertThat(req.getGrades().get(0).getComment()).isEqualTo("回答完整");
        assertThat(req.getOverallComment()).isEqualTo("总体良好");
        assertThat(req.getInvalid()).isFalse();
    }

    @Test
    void 作废请求体按invalid_true解析() throws Exception {
        MarkRespDtos.SubmitResult result = new MarkRespDtos.SubmitResult();
        result.setAttemptId(ATTEMPT);
        result.setStatus(4);
        when(markService.submit(eq("13"), eq(ATTEMPT), any())).thenReturn(result);

        mockMvc.perform(post("/api/exam/mark/paper/{attemptId}", ATTEMPT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grades\":[],\"invalid\":true}")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(4));

        ArgumentCaptor<MarkDtos.SubmitReq> captor = ArgumentCaptor.forClass(MarkDtos.SubmitReq.class);
        org.mockito.Mockito.verify(markService).submit(eq("13"), eq(ATTEMPT), captor.capture());
        assertThat(captor.getValue().getInvalid()).isTrue();
    }

    @Test
    void 学员待办返回200() throws Exception {
        MarkRespDtos.StudentTodoItem item = new MarkRespDtos.StudentTodoItem();
        item.setAttemptId(ATTEMPT);
        item.setArrangeId(ARRANGE);
        item.setArrangeName("默认考试");
        item.setFullname("管理员");
        item.setDeptName("杰克科技股份");
        item.setSubmitAt(LocalDateTime.of(2026, 11, 10, 13, 0, 0));
        item.setMarkStatus(0);
        when(markService.studentTodo(eq("13"), any())).thenReturn(PageResult.of(List.of(item), 1));

        mockMvc.perform(get("/api/exam/mark/student/todo")
                        .param("markStatus", "0")
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records[0].attemptId").value(ATTEMPT))
                .andExpect(jsonPath("$.data.records[0].arrangeName").value("默认考试"))
                .andExpect(jsonPath("$.data.records[0].submitAt").value("2026-11-10 13:00:00"));
    }

    @Test
    void 头部信息返回200() throws Exception {
        when(markService.base("13", ARRANGE)).thenReturn(Map.of(
                "arrangeId", ARRANGE,
                "name", "默认考试",
                "totalScore", new BigDecimal("18.00"),
                "passScore", new BigDecimal("10.80"),
                "pendingQty", 1,
                "blankScoring", false));

        mockMvc.perform(get("/api/exam/mark/{arrangeId}/base", ARRANGE)
                        .header("Authorization", "Bearer t"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("默认考试"))
                .andExpect(jsonPath("$.data.passScore").value(10.80))
                // DER-14：v1 不支持按空打分，后端显式下发 false
                .andExpect(jsonPath("$.data.blankScoring").value(false));
    }

    @Test
    void 无批阅权限时HTTP仍200但code非零() throws Exception {
        when(markService.paper("13", ATTEMPT))
                .thenThrow(new BizException(40003, "无批阅权限：你不是该考试的创建人或指定批阅人"));

        mockMvc.perform(get("/api/exam/mark/paper/{attemptId}", ATTEMPT)
                        .header("Authorization", "Bearer t"))
                // 本仓库统一约定：业务失败 HTTP 200 + 非零 code
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40003))
                .andExpect(jsonPath("$.message").value("无批阅权限：你不是该考试的创建人或指定批阅人"));
    }

    @Test
    void 未登录时按40023拒绝而不是40101() throws Exception {
        // 40101 归网关，前端见到会清凭证跳登录；本接口只是没拿到身份，不该触发登出
        when(currentUserService.userId(any())).thenReturn("0");

        mockMvc.perform(get("/api/exam/mark/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40023));
    }
}
