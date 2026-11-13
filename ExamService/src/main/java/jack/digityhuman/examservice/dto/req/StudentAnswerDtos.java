package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/**
 * 学员端答题请求容器（04 §11.8 / §11.9 / §11.10 / §11.11）。
 *
 * <p>字段名照抄契约：前端 `types/studentExam.ts` 的 `AnswerSaveItem` 等已按契约写死。
 */
public final class StudentAnswerDtos {

    private StudentAnswerDtos() {
    }

    /**
     * §11.8 逐题保存的单条作答。
     *
     * <p>`answer` 的语义按题型分派（03 §10B.5）：
     * 单选/多选/判断存**选项 id** 数组，填空按空顺序存文本数组，问答存单元素文本数组。
     * 存 id 而不是字母，是为了选项顺序变化后判分仍正确。
     */
    @Data
    public static class AnswerItem {
        private String quesId;
        private Integer quesType;
        /** 客观题=选项 id 数组；填空=按空文本数组；问答=单元素文本数组 */
        private List<String> answer;
        /** 问答附件 URL 数组 */
        private List<String> attach;
        /** 1=已标记 */
        private Integer marked;
        /**
         * 前端提交时刻（毫秒时间戳）。
         *
         * <p>本期不落库：`exam_attempt_answer` 只有 `answer_time`（作答时间），
         * 没有"客户端提交时刻"列。保留字段是为了前端不用改，且将来做离线补交时能用上。
         */
        private Long lastSubmitTime;
    }

    /** §11.8 批量保存请求体。 */
    @Data
    public static class SaveAnswersReq {
        private List<AnswerItem> answers;
    }

    /** §11.9 题目标记（独立于答案保存：学员可能只标记不答）。 */
    @Data
    public static class FlagReq {
        private String quesId;
        /** 0 未标记 1 已标记；其它值按 1 处理（前端只会传这两种） */
        private Integer marked;
    }

    /**
     * §11.10 切屏等异常事件上报。
     *
     * <p>`clientEventId` 本期只用于日志去重排查，不落库：
     * 契约只要求"累计切屏次数"，没有事件明细表。
     */
    @Data
    public static class AntiCheatReq {
        /** 如 `screen.hidden` / `screen.blur` */
        private String eventType;
        private String occurredAt;
        /** 前端生成的幂等 id，用于排查重复上报 */
        private String clientEventId;
    }

    /** §11.11 交卷。 */
    @Data
    public static class SubmitReq {
        /** 前端自报用时；服务端只作参考，真实用时按 start_at 计算 */
        private Integer usedSeconds;
        /** 1 主动 / 2 超时自动 / 3 管理员；缺省按 1 */
        private Integer submitType;
    }
}
