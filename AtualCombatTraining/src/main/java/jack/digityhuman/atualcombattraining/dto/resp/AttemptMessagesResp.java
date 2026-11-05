package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * attempt 消息历史（契约 04 §API-A05 `GET /student/attempts/{attemptId}/messages`）。
 * <p>
 * <b>为什么要这个接口</b>：运行态页原来只用会话内的 WebSocket 消息，一旦刷新页面聊天记录就空了。
 * 数据库 `act_attempt_message` 一直有落库（user / assistant / coach 三类），只是没暴露读接口。
 * 契约还要求"刷新/重连先调本接口，再连接 WS"，所以这里同时把**轮次状态**下发，
 * 让前端不必靠内存恢复进度。
 * <p>
 * <b>role 语义</b>（与 WS 帧、落库口径一致，不改帧名）：
 * <ul>
 *   <li>{@code user} —— 学员发言；</li>
 *   <li>{@code assistant} —— AI 演员台词；</li>
 *   <li>{@code coach} —— 教练点评（仅训练模式且非空时才落库，前端据此单独配色）；</li>
 *   <li>{@code system} —— 系统事件。</li>
 * </ul>
 */
@Data
public class AttemptMessagesResp {

    private String attemptId;
    /** 本次返回的消息条数（= messages.size()，不是全量总数） */
    private int total;
    /** 该 attempt 消息的全量条数（分页时前端据此判断"还有没有更多"） */
    private long totalCount;
    /** 请求的游标（返回 seq > afterSeq 的消息；0 表示从头取） */
    private Long afterSeq;
    /** 按 seq 升序 */
    private List<Message> messages;
    /** 同一次请求的轮次状态（契约 API-A05「返回消息和轮次状态」） */
    private TurnState turn;

    /** 单条消息 */
    @Data
    public static class Message {
        private String id;
        private Long seq;
        /** user / assistant / coach / system */
        private String role;
        /** text / voice / event */
        private String messageType;
        private String textContent;
        /** 是否教练点评（等价于 role=coach，前端不必自己再判断一遍） */
        private boolean coach;
        private LocalDateTime createdAt;
    }

    /**
     * 轮次状态：刷新/重连后前端用它恢复进度条与剩余时间，不用等 WS 下一帧。
     * 全部取自 attempt 行本身（不额外猜）。
     */
    @Data
    public static class TurnState {
        private Integer currentRound;
        private Integer maxRound;
        /** 练习目标达成度 0~100 */
        private Integer targetProgress;
        /** 剩余秒数（deadlineAt - now；未开始或已结束为 null） */
        private Integer remainingSeconds;
        private String status;
    }
}
