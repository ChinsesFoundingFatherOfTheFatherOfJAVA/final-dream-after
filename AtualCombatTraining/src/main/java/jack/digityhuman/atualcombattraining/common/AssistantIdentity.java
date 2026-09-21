package jack.digityhuman.atualcombattraining.common;

/**
 * AI 设计助手身份常量
 * <p>
 * 内网系统暂无统一登录体系：用户侧固定为管理员，助手侧固定为编排智能体标识。
 * 消息落库表无 sender 列，历史接口按 role 还原 sender，故两侧取值集中在这里定义。
 */
public final class AssistantIdentity {

    /** 用户发送者 ID */
    public static final String USER_ID = "admin";
    /** 用户发送者显示名 */
    public static final String USER_NAME = "管理员";
    /** 助手（编排智能体 Nova）标识，作为 message-start 的 sender.id */
    public static final String AGENT_ID = "无所不知的大贤者";
    /** 助手显示名 */
    public static final String AGENT_NAME = "无所不知的大贤者";

    private AssistantIdentity() {
    }
}