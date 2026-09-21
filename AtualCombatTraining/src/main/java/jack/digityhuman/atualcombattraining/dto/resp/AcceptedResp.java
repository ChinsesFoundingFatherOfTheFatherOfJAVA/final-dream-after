package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 异步受理响应（03 文档 2.5.3 / 2.5.4）
 * 生成类工具一律"受理即返回"，真实结果通过 WS 事件推送。
 * <p>
 * hint 是给**模型**看的软约束提示（会作为工具观察结果进入下一轮上下文）：
 * 用于抑制"越过用户确认直接生成""受理后重复调用同一工具"这类行为。
 * 注意它是提示而非拦截——真正的闸门仍在编排智能体的提示词里。
 */
@Data
public class AcceptedResp {

    private Boolean accepted;
    /** 给模型的软约束提示（可空） */
    private String hint;

    public static AcceptedResp of() {
        return of(null);
    }

    public static AcceptedResp of(String hint) {
        AcceptedResp resp = new AcceptedResp();
        resp.setAccepted(true);
        resp.setHint(hint);
        return resp;
    }
}