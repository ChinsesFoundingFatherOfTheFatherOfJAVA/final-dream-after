package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 随机练习取档响应（契约 08 §4 随机分支；2026-11-04 新增）。
 * <p>
 * 对应原平台 `randomScript?difficulty=N` 的返回：一份已预生成的 drill-script-v1。
 * 前端拿到后按脚本里的 actor/scene 渲染确认弹窗，点「开始训练」时把 attempt 创建参数带上
 * （本地口径：创建 attempt 时服务端会再按同一档取一次档并冻结快照，见 AttemptLifecycleServiceImpl）。
 */
@Data
public class RandomScriptResp {

    /** 实际命中的难度档（可能因降级与请求值不同，前端据此显示真实难度） */
    private Integer difficulty;
    /** 请求的难度档（保留原始值，便于排查降级） */
    private Integer requestedDifficulty;
    /** 是否发生了降级（本档无候选 → 其他档 / 案例正文）。true 时前端可给"该档暂用近似内容"提示 */
    private boolean degraded;
    /** drill-script-v1 全文（JSON 字符串，前端解析后渲染） */
    private String script;
}
