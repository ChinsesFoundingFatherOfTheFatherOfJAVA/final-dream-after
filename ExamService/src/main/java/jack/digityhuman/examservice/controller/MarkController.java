package jack.digityhuman.examservice.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.MarkDtos;
import jack.digityhuman.examservice.dto.resp.MarkRespDtos;
import jack.digityhuman.examservice.service.MarkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 批阅接口（04 §11B.1–§11B.5）：`/api/exam/mark`。
 *
 * <p>与 `StudentResultController` 分开：那份是学员看自己的卷（按 attempt 归属鉴权），
 * 这份是批阅人看别人的卷（按考试批阅权限鉴权）。两者的越权模型完全不同，
 * 合在一个类里最容易发生的事故是「把学员接口的归属校验抄给批阅接口」，
 * 于是批阅人只能批自己的卷——或者反过来，学员能读别人的卷。
 *
 * <p>**为什么 `/student/todo` 放在本类而不是学员端 Controller**：
 * 它判定的是「谁有批阅权」，与这里其余端点共用同一条规则（DER-08）。
 * 放到学员端会让批阅权限判定出现第二份实现。
 */
@RestController
@RequestMapping("/api/exam/mark")
@RequiredArgsConstructor
public class MarkController {

    private final MarkService markService;
    private final CurrentUserService currentUserService;

    /** §11B.1 批阅列表（考试维度，含待批阅计数）。 */
    @GetMapping("/list")
    public ApiResponse<PageResult<MarkRespDtos.MarkExamItem>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer arrangeType,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer limit,
            @RequestParam(required = false) String orderby,
            @RequestParam(required = false, defaultValue = "DESC") String direction,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        MarkService.Query query = new MarkService.Query();
        query.setKeyword(keyword);
        query.setArrangeType(arrangeType);
        query.setStartDate(startDate);
        query.setEndDate(endDate);
        query.setCurrent(current);
        query.setLimit(limit);
        query.setOrderby(orderby);
        query.setDirection(direction);
        return ApiResponse.success(markService.list(requireUser(authorization), query));
    }

    /** §11B.2 批阅页头部信息（考试名、总分、通过分、批阅人设置）。 */
    @GetMapping("/{arrangeId}/base")
    public ApiResponse<Map<String, Object>> base(
            @PathVariable String arrangeId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(markService.base(requireUser(authorization), arrangeId));
    }

    /**
     * §11B.2 某考试的答卷列表。
     *
     * <p>**同一学员多次作答逐条返回**，不合并——合并会让批阅人看不到历史答卷，
     * 而补考/申诉恰恰要看历史。
     */
    @GetMapping("/{arrangeId}/users")
    public ApiResponse<PageResult<MarkRespDtos.MarkUserItem>> users(
            @PathVariable String arrangeId,
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer markStatus,
            @RequestParam(required = false) String orderby,
            @RequestParam(required = false, defaultValue = "DESC") String direction,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        MarkService.UsersQuery query = new MarkService.UsersQuery();
        if (current != null) {
            query.setCurrent(current);
        }
        if (limit != null) {
            query.setLimit(limit);
        }
        query.setMarkStatus(markStatus);
        if (orderby != null) {
            query.setOrderby(orderby);
        }
        query.setDirection(direction);
        return ApiResponse.success(markService.users(requireUser(authorization), arrangeId, query));
    }

    /**
     * §11B.3 批阅答卷详情。
     *
     * <p>**不受 `answerView` 限制**：批阅人始终可见全部题干、作答、标准答案、解析与评语。
     */
    @GetMapping("/paper/{attemptId}")
    public ApiResponse<MarkRespDtos.PaperDetail> paper(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(markService.paper(requireUser(authorization), attemptId));
    }
    /**
     * 批阅队列（2026-11-13）：同场考试待批阅答卷的有序 id 列表 + 当前答卷下标。
     *
     * <p>供批阅页左侧「1/N」考生切换栏使用；原平台批阅页可以在同页内连续翻下一份，
     * 我方原先只能退回答卷列表再点，批多份时操作次数翻倍。
     */
    @GetMapping("/paper/{attemptId}/queue")
    public ApiResponse<MarkRespDtos.MarkQueue> queue(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(markService.queue(requireUser(authorization), attemptId));
    }

    /**
     * §11B.4 提交批阅。
     *
     * <p>已批阅答卷可重复提交（重新批阅）；总分由逐题分数重算，**不累加**。
     * `invalid=true` 走 `status=4 已作废`，不参与统计与通过率。
     */
    @PostMapping("/paper/{attemptId}")
    public ApiResponse<MarkRespDtos.SubmitResult> submit(
            @PathVariable String attemptId,
            @RequestBody(required = false) MarkDtos.SubmitReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(markService.submit(requireUser(authorization), attemptId, req));
    }

    /** §11B.5 学员端批阅待办（批阅人 = 当前用户的精简视图）。 */
    @GetMapping("/student/todo")
    public ApiResponse<PageResult<MarkRespDtos.StudentTodoItem>> studentTodo(
            @RequestParam(required = false) Integer current,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer markStatus,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        MarkService.TodoQuery query = new MarkService.TodoQuery();
        if (current != null) {
            query.setCurrent(current);
        }
        if (limit != null) {
            query.setLimit(limit);
        }
        query.setMarkStatus(markStatus);
        return ApiResponse.success(markService.studentTodo(requireUser(authorization), query));
    }

    /**
     * 取当前用户；解析不出按 40023 拒绝。
     *
     * <p>刻意不用 40101：40101 归网关，前端见到它会清凭证跳登录；
     * 而这里只是「本接口没拿到身份」，让用户重登是过度反应。
     */
    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if (userId == null || userId.isBlank() || "0".equals(userId)) {
            throw new BizException(40023, "未获取到登录用户，请重新登录");
        }
        return userId;
    }
}
