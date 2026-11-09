package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 设置案例形象响应（2026-11-05）。
 * <p>
 * 回填实际定位到的场景名/案例名与生效 URL，便于管理端在"同名多例"被消歧后确认改对了对象；
 * {@code columnUpdated} 是本次更新到 {@code act_case_script} 的行数：
 * <b>0 并不代表失败</b>——案例尚未确认落库时（artifact 有、表里还没有行）只有 artifact 被写入，
 * 这次确认落库会按 artifact 重建出带形象的行。
 */
@Data
public class CaseAvatarResp {

    private String themeId;
    /** 实际命中的场景名（由 artifact 回填，不是入参原样回显） */
    private String scenarioName;
    /** 实际命中的案例标题 */
    private String scriptName;
    /** 生效的形象 URL；null=已清除覆盖 */
    private String avatarUrl;
    /** 写入后的 artifact 版本号 */
    private Integer artifactVersionNo;
    /** 本次同步更新的 act_case_script 行数（0=案例尚未落库，确认后自动带上） */
    private Integer columnUpdated;
}
