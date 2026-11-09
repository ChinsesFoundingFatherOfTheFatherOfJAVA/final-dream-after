package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

/**
 * 设置案例形象请求（管理端「换形象」，2026-11-05）。
 * <p>
 * <b>为什么需要同时定位到 artifact 与列</b>：ACT 的确认落库是<b>先删整版案例行、再按 artifact 重插</b>
 * （{@code ConfirmServiceImpl.clearCurrentVersionRows}）。只写 {@code act_case_script.actor_avatar_url}
 * 列的话，管理端再点一次确认，这一列会随整行被删掉、图直接丢失。
 * 所以本接口必须把值写进 artifact，让确认落库能把它带回来。
 * <p>
 * <b>定位键的选择</b>：
 * <ul>
 *   <li>{@code scriptName}（推荐）：artifact 里的案例<b>没有 id</b>，只有 scriptName；
 *       且行 ID 在每次确认后都会重新生成，用 ID 定位不可持久。</li>
 *   <li>{@code scriptId}（{@code act_case_script.id}）：仅作便利入口——服务端先查行取出
 *       scriptName 与所属场景名，再回 artifact 定位。<b>确认过一次后旧 ID 即失效</b>。</li>
 *   <li>{@code scenarioName}（可选）：同一主题下不同场景可能有同名案例，传它来消歧；
 *       不传且匹配到多条时报"歧义"，不猜。</li>
 * </ul>
 */
@Data
public class CaseAvatarSaveReq {

    /** 场景名（可选，用于消歧）。与 artifact 的 scenarios[].name 对齐 */
    private String scenarioName;
    /** 案例标题（与 artifact 的 scenarios[].cases[].scriptName 对齐） */
    private String scriptName;
    /** 案例行 ID（act_case_script.id，仅便利入口；确认后失效） */
    private String scriptId;
    /**
     * 形象 URL。传 null 或空串=清除覆盖（回落到自动定图）。
     * <p>
     * 允许两种形态：本地素材相对路径（{@code /avatar/xxx.png}，与 FileStorageService
     * 的 avatar-url-prefix 一致）或完整 http(s) URL（AI 生成结果）。
     */
    private String avatarUrl;
}
