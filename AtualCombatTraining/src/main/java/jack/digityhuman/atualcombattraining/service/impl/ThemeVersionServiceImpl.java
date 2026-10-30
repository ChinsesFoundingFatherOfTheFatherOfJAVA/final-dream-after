package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.RollbackResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSettingsResp;
import jack.digityhuman.atualcombattraining.dto.resp.VersionResp;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ScenarioUnitService;
import jack.digityhuman.atualcombattraining.service.ThemeService;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.atualcombattraining.service.ThemeVersionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * 主题版本服务实现（2026-10-30 版本语义重构后的接口门面）
 * <p>
 * 职责变化（对比旧实现）：
 * <ul>
 *   <li>设置读/写全部委托 {@link ThemeSettingsService}（act_theme_settings 一行，不升版）；</li>
 *   <li>版本历史只列已发布版本（1..published_version_no）；v0 工作版本不对用户返回；</li>
 *   <li>回滚改为"公开指针回切"：published/working 一起切到目标已发布版本，不新增版本行；</li>
 *   <li>内容版本的创建/发布走 {@link ContentVersionService}（首次发布、AI 确认升版），不再由本类负责。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeVersionServiceImpl implements ThemeVersionService {

    /** 已结束（终态）状态值 */
    private static final int STATUS_FINISHED = 5;
    /** 剩余企业容量：企业级占位常量，后端固定返回，不落库不扣减 */
    private static final int REMAINING_ENTERPRISE_CAPACITY = 2;
    /** 版本创建者（登录体系不在本次范围，写死） */
    private static final String CREATOR = "管理员";
    /** 占位负责人 */
    private static final String OWNER_USER_ID = "admin";

    private final ActThemeMapper themeMapper;
    private final ActThemeVersionMapper versionMapper;
    private final ThemeService themeService;
    private final ScenarioUnitService scenarioUnitService;
    private final ThemeSettingsService themeSettingsService;

    /**
     * 设置读取：五表单来自 act_theme_settings（全局、与版本无关），
     * 内容概览按 working_version_no 统计（首次发布前统计 v0 工作内容）。
     */
    @Override
    public ThemeSettingsResp getSettings(String themeId) {
        ActTheme theme = requireTheme(themeId);
        ActThemeSettings settings = themeSettingsService.require(themeId);
        int workingVersionNo = theme.getWorkingVersionNo() == null ? 0 : theme.getWorkingVersionNo();

        ThemeSettingsResp resp = new ThemeSettingsResp();
        resp.setVersionNo(String.valueOf(
                theme.getPublishedVersionNo() == null ? 0 : theme.getPublishedVersionNo()));

        ThemeSettingsResp.Basic basic = new ThemeSettingsResp.Basic();
        // 名称/描述以 act_theme 冗余列为准（设置保存时同步），版本行里只是历史快照
        basic.setThemeName(theme.getThemeName());
        basic.setThemeDesc(theme.getThemeDesc());
        basic.setOwnerUserId(settings.getOwnerUserId() == null || settings.getOwnerUserId().isBlank()
                ? OWNER_USER_ID : settings.getOwnerUserId());
        basic.setPlanStartTime(settings.getPlanStartTime());
        basic.setPlanEndTime(settings.getPlanEndTime());
        resp.setBasic(basic);

        ThemeSettingsResp.Access access = new ThemeSettingsResp.Access();
        access.setTotalDrillCount(settings.getTotalDrillCount());
        access.setRemainingEnterpriseCapacity(REMAINING_ENTERPRISE_CAPACITY);
        access.setPerSceneLimit(settings.getPerSceneLimit());
        access.setJoinMode(settings.getJoinMode());
        access.setGuestAccessEnabled(toBool(settings.getGuestAccessEnabled()));
        resp.setAccess(access);

        ThemeSettingsResp.Strategy strategy = new ThemeSettingsResp.Strategy();
        strategy.setAiResponseMode(settings.getAiResponseMode());
        strategy.setTrainingModeEnabled(toBool(settings.getTrainingModeEnabled()));
        strategy.setAssessmentModeEnabled(toBool(settings.getAssessmentModeEnabled()));
        strategy.setCoachReviewEnabled(toBool(settings.getCoachReviewEnabled()));
        strategy.setSuggestionEnabled(toBool(settings.getSuggestionEnabled()));
        strategy.setPracticeMethods(splitCsv(settings.getPracticeMethods()));
        strategy.setDifficulty(settings.getDifficulty());
        strategy.setAllowCustomDifficulty(toBool(settings.getAllowCustomDifficulty()));
        ThemeSettingsResp.AntiCheat antiCheat = new ThemeSettingsResp.AntiCheat();
        antiCheat.setFaceCheckEnabled(toBool(settings.getFaceCheckBeforeEnabled()));
        antiCheat.setFaceCheckStage(toBool(settings.getFaceCheckOngoingEnabled()) ? "ongoing" : "before");
        antiCheat.setScreenSwitchMode(settings.getScreenSwitchMode());
        antiCheat.setScreenSwitchLimit(settings.getScreenSwitchLimit());
        strategy.setAntiCheat(antiCheat);
        resp.setStrategy(strategy);

        ThemeSettingsResp.PassStandard passStandard = new ThemeSettingsResp.PassStandard();
        passStandard.setScenePassType(settings.getScenePassType());
        passStandard.setScenePassCount(settings.getScenePassCount());
        resp.setPassStandard(passStandard);

        ThemeSettingsResp.ContentOverview overview = new ThemeSettingsResp.ContentOverview();
        overview.setScenarioUnitCount((int) scenarioUnitService.countUnits(themeId, workingVersionNo));
        overview.setCustomerProfileCount(theme.getCustomerProfileCount());
        overview.setIcebergElementCount(
                scenarioUnitService.countDistinctIcebergElements(themeId, workingVersionNo));
        overview.setEvaluationDimensionCount(
                scenarioUnitService.countDistinctDimensionNames(themeId, workingVersionNo));
        resp.setContentOverview(overview);
        return resp;
    }

    /**
     * 设置保存：委托 ThemeSettingsService（不产生版本），响应里 versionNo 语义变为
     * "当前公开版本号"（未发布主题为 0），前端只用于展示不用于回滚定位。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ThemeSaveResp saveSettings(String themeId, ThemeSettingsSaveReq req) {
        ActTheme theme = requireTheme(themeId);
        if (Objects.equals(theme.getStatus(), STATUS_FINISHED)) {
            throw new BizException("主题已结束，无法保存设置");
        }
        String themeName = req == null || req.getBasic() == null ? null : req.getBasic().getThemeName();
        String themeDesc = req == null || req.getBasic() == null ? null : req.getBasic().getThemeDesc();
        ThemeSettingsService.SaveResult result = themeSettingsService.save(
                themeId, req, themeName, themeDesc, theme.getPublishedVersionNo());

        ThemeSaveResp resp = new ThemeSaveResp();
        resp.setId(theme.getId());
        resp.setVersionNo(String.valueOf(
                theme.getPublishedVersionNo() == null ? 0 : theme.getPublishedVersionNo()));
        resp.setThemeMode(result.themeMode());
        resp.setAssignMode(result.assignMode());
        return resp;
    }

    /**
     * 版本历史：只列已发布版本（1..published_version_no），version_no 倒序。
     * current=true 标在 published_version_no 上；未发布主题返回空列表（v0 不暴露）。
     */
    @Override
    public List<VersionResp> listVersions(String themeId) {
        ActTheme theme = requireTheme(themeId);
        Integer published = theme.getPublishedVersionNo();
        if (published == null) {
            return List.of();
        }
        List<ActThemeVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<ActThemeVersion>()
                        .eq(ActThemeVersion::getThemeId, themeId)
                        .ge(ActThemeVersion::getVersionNo, 1)
                        .le(ActThemeVersion::getVersionNo, published)
                        .orderByDesc(ActThemeVersion::getVersionNo));
        return versions.stream().map(v -> toVersionResp(v, published)).toList();
    }

    /**
     * 回滚（新语义）：把公开指针与工作指针一起切回目标已发布版本。
     * 不新增版本行；目标必须是已发布范围（1..published_version_no）。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RollbackResp rollback(String themeId, Integer versionNo) {
        ActTheme theme = requireTheme(themeId);
        if (Objects.equals(theme.getStatus(), STATUS_FINISHED)) {
            throw new BizException("主题已结束，无法回滚");
        }
        Integer published = theme.getPublishedVersionNo();
        if (published == null) {
            throw new BizException("主题尚未发布，无可回滚版本");
        }
        if (versionNo == null || versionNo < 1 || versionNo > published) {
            throw new BizException("目标版本不在已发布范围内");
        }
        ActThemeVersion target = requireVersion(themeId, versionNo);
        themeMapper.update(null, new LambdaUpdateWrapper<ActTheme>()
                .eq(ActTheme::getId, themeId)
                .set(ActTheme::getWorkingVersionNo, target.getVersionNo())
                .set(ActTheme::getPublishedVersionNo, target.getVersionNo())
                .set(ActTheme::getUpdatedAt, java.time.LocalDateTime.now()));
        themeService.syncRedundantFields(theme, target);

        RollbackResp resp = new RollbackResp();
        resp.setId(theme.getId());
        resp.setCurrentVersionNo(String.valueOf(target.getVersionNo()));
        return resp;
    }

    /** 版本实体转响应（current 由公开指针判定） */
    private VersionResp toVersionResp(ActThemeVersion v, Integer publishedVersionNo) {
        VersionResp resp = new VersionResp();
        resp.setVersionNo(String.valueOf(v.getVersionNo()));
        resp.setCurrent(Objects.equals(v.getVersionNo(), publishedVersionNo));
        resp.setCreatedAt(v.getCreatedAt());
        resp.setUsedUserCount(v.getUsedUserCount());
        resp.setCompletedCount(v.getCompletedCount());
        resp.setInProgressCount(v.getInProgressCount());
        resp.setCreator(v.getCreator() == null || v.getCreator().isBlank() ? CREATOR : v.getCreator());
        return resp;
    }

    private ActTheme requireTheme(String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        return theme;
    }

    /** 查指定版本，不存在抛业务异常 */
    private ActThemeVersion requireVersion(String themeId, Integer versionNo) {
        ActThemeVersion version = versionMapper.selectOne(new LambdaQueryWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getThemeId, themeId)
                .eq(ActThemeVersion::getVersionNo, versionNo)
                .last("LIMIT 1"));
        if (version == null) {
            throw new BizException("目标版本不存在");
        }
        return version;
    }

    /** Integer(0/1) 转 Boolean */
    private static boolean toBool(Integer value) {
        return Objects.equals(value, 1);
    }

    /** CSV 字符串拆为数组（过滤空项） */
    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
