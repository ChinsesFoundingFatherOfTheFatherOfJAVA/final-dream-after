package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.common.PageResult;
import jack.digityhuman.atualcombattraining.dto.req.ThemeListReq;
import jack.digityhuman.atualcombattraining.dto.resp.ScenarioUnitResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeDetailResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeListResp;
import jack.digityhuman.atualcombattraining.dto.resp.StatusResp;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;

import java.util.List;

/**
 * 训练主题服务（FP-06 列表 / FP-07-08 详情 / FP-09 状态机）
 */
public interface ThemeService {

    /** 分页查询主题列表（status/themeMode/keyword 过滤；占位字段接收不过滤） */
    PageResult<ThemeListResp> list(ThemeListReq req);

    /** 主题详情：头部信息 + 统计占位 + 当前版本单元数 + 版本总数 */
    ThemeDetailResp detail(String id);

    /** FP-11 当前版本场景单元列表（含评估维度，按 sortOrder 升序） */
    List<ScenarioUnitResp> listScenarioUnits(String id);

    /** 暂停/恢复：已发布(3)⇄已暂停(4)，其余状态拒绝 */
    StatusResp pause(String id);

    /** 结束：已发布(3)/已暂停(4)→已结束(5)，终态不可逆 */
    StatusResp finish(String id);

    /**
     * 同步主题表冗余字段（保存设置与回滚共用）：
     * theme_name/theme_desc/theme_mode/assign_mode/difficulty 取版本值，
     * current_version_no 切到该版本，updated_at 刷新，并落库。
     */
    void syncRedundantFields(ActTheme theme, ActThemeVersion version);
}
