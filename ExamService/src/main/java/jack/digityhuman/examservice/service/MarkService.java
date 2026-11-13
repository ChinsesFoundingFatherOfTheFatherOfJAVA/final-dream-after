package jack.digityhuman.examservice.service;

import jack.digityhuman.common.api.PageResult;
import jack.digityhuman.examservice.dto.req.MarkDtos;
import jack.digityhuman.examservice.dto.resp.MarkRespDtos;

/**
 * 批阅模块（04 §11B.1–§11B.5，01 §4C M1–M4）。
 *
 * <p><b>批阅人范围（DER-08）</b>：本期只做「创建人 + 指定批阅人」两种。
 * 原平台的部门经理/直属经理/定向三种不做——「谁算部门经理」的判定口径未定义
 * （一个部门可能有多个负责人），口径不定就是猜。超管始终可批。
 *
 * <p><b>为什么「谁能批」要集中在一个类里判定</b>：M1 列表、M2 答卷列表、M3 详情、
 * 11B.5 学员待办四个入口用的是**同一条**可见性规则。任何一处单独实现，
 * 都会出现「列表里看得到、点进去说无权限」或反过来的割裂。
 */
public interface MarkService {

    /** §11B.1 批阅列表（考试维度，含待批阅计数）。 */
    PageResult<MarkRespDtos.MarkExamItem> list(String userId, Query query);

    /**
     * §11B.2 批阅页头部信息（考试名、总分、通过分、批阅人设置）。
     *
     * <p>返回 `Map` 而不是专门的 DTO：这份数据只被前端当只读展示用，
     * 且字段集合还会随 M3 页面演进（导出按钮、印章数据等），
     * 单独建类会让每次页面微调都改后端签名。
     */
    java.util.Map<String, Object> base(String userId, String arrangeId);

    /** §11B.2 某考试的答卷列表（同一学员多次作答逐条返回，不合并）。 */
    PageResult<MarkRespDtos.MarkUserItem> users(String userId, String arrangeId, UsersQuery query);

    /** §11B.3 批阅答卷详情（**不受 answerView 限制**，批阅人始终可见全部内容）。 */
    MarkRespDtos.PaperDetail paper(String userId, String attemptId);

    /**
     * 批阅队列：同一场考试待批阅的答卷 id 列表 + 当前答卷的下标
     * （2026-11-13，对齐原平台批阅页左侧「1/N」考生切换栏）。
     */
    MarkRespDtos.MarkQueue queue(String userId, String attemptId);

    /**
     * §11B.4 提交批阅。
     *
     * <p>幂等性：已批阅的答卷可重复提交（重新批阅），**重算不累加**——
     * 总分永远由「逐题 score 求和」得出，不是 `原总分 + 本次打分`。
     */
    MarkRespDtos.SubmitResult submit(String userId, String attemptId, MarkDtos.SubmitReq req);

    /** §11B.5 学员端批阅待办（批阅人 = 当前用户的精简视图）。 */
    PageResult<MarkRespDtos.StudentTodoItem> studentTodo(String userId, TodoQuery query);

    // ---------------- 查询条件 ----------------

    /** §11B.1 列表筛选。 */
    @lombok.Data
    class Query {
        private String keyword;
        private Integer arrangeType;
        /** 发布/开始日期区间（yyyy-MM-dd，含边界） */
        private String startDate;
        private String endDate;
        private Integer current = 1;
        private Integer limit = 10;
        private String orderby;
        private String direction = "DESC";
    }

    /** §11B.2 答卷列表筛选。 */
    @lombok.Data
    class UsersQuery {
        private Integer current = 1;
        private Integer limit = 10;
        /** 0 待批阅 / 1 已批阅；null 全部 */
        private Integer markStatus;
        private String orderby = "submitTime";
        private String direction = "DESC";
    }

    /** §11B.5 待办筛选。 */
    @lombok.Data
    class TodoQuery {
        private Integer current = 1;
        private Integer limit = 10;
        /** 0 待批阅 / 1 已批阅；null 全部 */
        private Integer markStatus;
    }
}
