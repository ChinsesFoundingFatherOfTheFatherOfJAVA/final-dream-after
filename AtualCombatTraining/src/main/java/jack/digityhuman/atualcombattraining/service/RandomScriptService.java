package jack.digityhuman.atualcombattraining.service;

/**
 * 随机练习脚本服务（契约 08 §4 随机分支；2026-11-04 改为发布预生成 + 取档）。
 * <p>
 * <b>为什么这么改</b>：抓包实测（`tmp/investigation-R2R3.md` §4.3）证明原平台随机脚本是在
 * **发布阶段**按「场景 × 难度档」预生成落库的，学员端 `randomScript?difficulty=N` 只是取档、
 * 不调模型（从从未练习过的场景 3/4 直接取档，产物 create_time 仍是发布窗口且无新建）。
 * 本项目此前"开练现场调 AG-D01 生成随机脚本"与实测不符。
 * <p>
 * <b>与「再练一次」的关系（用户已拍板）</b>：再练一次**不走取档**，直接复用上次 attempt 的
 * 角色快照（`scriptSnapshotJson`），保证"不换脸"。取档只服务「新建随机练习」。
 * <p>
 * <b>与案例难度档的复用</b>：若某难度档恰好等于该场景案例自带的难度，预生成时直接复用
 * 案例脚本正文，不重复生成（原平台实测同款行为：`a2_d2` 与场景 02 案例是同一个 artifact）。
 */
public interface RandomScriptService {

    /**
     * 取档结果。带「实际难度」与「是否降级」，让前端能如实展示（而不是把降级档当请求档）。
     *
     * @param script              drill-script-v1 全文
     * @param actualDifficulty    实际命中的难度档
     * @param requestedDifficulty 请求的难度档
     * @param degraded            true = 本档无候选，走了降级链（其他档或案例正文）
     */
    record PickResult(String script, int actualDifficulty, int requestedDifficulty, boolean degraded) {
    }

    /**
     * 取档：从 (场景, 难度档) 的候选中随机取一份。
     * <p>
     * 降级链（不静默返回空）：本档候选为空 → 该场景其他难度档 → 该场景案例脚本 → 抛业务异常。
     *
     * @throws jack.digityhuman.common.api.BizException 该场景完全没有随机脚本与案例脚本时
     */
    PickResult pick(String themeId, int versionNo, String scenarioUnitId, int difficulty);

    /**
     * 发布期预生成：为「每个场景 × 每个难度档（1/2/3）」生成 {@code CANDIDATES_PER_BUCKET} 份候选并落库。
     * <p>
     * 幂等：先删该版本全部随机分行再插。命中案例难度档时复用案例正文，不调模型。
     * 单个档位生成失败只记日志并跳过（该档取档时由降级链兜底），不让整次发布失败。
     *
     * @return 成功落库的候选总数
     */
    int pregenerate(String themeId, int versionNo);
}
