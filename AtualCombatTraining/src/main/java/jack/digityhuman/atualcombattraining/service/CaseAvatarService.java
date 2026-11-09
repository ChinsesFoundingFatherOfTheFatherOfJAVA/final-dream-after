package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.req.CaseAvatarSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.CaseAvatarResp;

/**
 * 案例形象设置服务（L0 上传覆盖的写入侧，2026-11-05）。
 * <p>
 * <b>为什么这一层必须存在，而不能只写列</b>：
 * ACT 的确认落库是「先删整版案例行，再按 artifact 重插」（{@code ConfirmServiceImpl:198}）。
 * 只写 {@code act_case_script.actor_avatar_url} 列，管理端下一次点「确认」时
 * 整行会被删掉重建，形象随之静默丢失——不会报错、单测也测不出，
 * 只有用户实际跑一轮才会发现「图没了」。
 * <p>
 * 因此本服务的核心契约是<b>双写</b>：
 * <ol>
 *   <li>写 artifact 的 {@code scenarios[].cases[].actorAvatarUrl}（权威源，确认落库时被读取重建）；</li>
 *   <li>写 {@code act_case_script.actor_avatar_url} 列（当前版本立即可见，发布时也读它对 L0 判断）；</li>
 * </ol>
 * 两步在<b>同一事务</b>内，任一失败整体回滚。
 */
public interface CaseAvatarService {

    /**
     * 设置（或清除）某案例的陪练者形象。
     * <p>
     * 定位优先用 {@code scriptName}（artifact 里案例没有 id，且行 ID 每次确认都会重建）；
     * {@code scriptId} 作为便利入口时，先查行取出 scriptName/场景名再回 artifact 定位。
     * 同主题下同名案例会歧义，此时要求入参带 {@code scenarioName} 消歧，<b>不猜</b>。
     *
     * @param req 定位键 + 目标 URL（null/空串=清除覆盖，回落自动定图）
     */
    CaseAvatarResp saveAvatar(String themeId, CaseAvatarSaveReq req);
}
