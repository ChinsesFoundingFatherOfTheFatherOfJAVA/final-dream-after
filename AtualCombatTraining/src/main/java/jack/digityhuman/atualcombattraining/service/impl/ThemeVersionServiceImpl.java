package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.dto.resp.RollbackResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSaveResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeSettingsResp;
import jack.digityhuman.atualcombattraining.dto.resp.VersionResp;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ScenarioUnitService;
import jack.digityhuman.atualcombattraining.service.ThemeService;
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
 * 主题版本服务实现（FP-13~17 设置读取 / FP-18 保存 / FP-19 版本列表与回滚 / AI 路径版本复制）
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

    /**
     * FP-13~17 读取当前版本五表单 + 内容概览：
     * 1. 查主题 → 2. 查当前版本行 → 3. 组装五表单（CSV/0-1 与数组/布尔互转）
     * 4. 内容概览：单元数实时 count、冰山合并去重、维度名合并去重、客户画像读主题表占位字段
     */
    @Override
    public ThemeSettingsResp getSettings(String themeId) {
        ActTheme theme = requireTheme(themeId);
        ActThemeVersion version = requireVersion(themeId, theme.getCurrentVersionNo());

        ThemeSettingsResp resp = new ThemeSettingsResp();
        resp.setVersionNo(String.valueOf(version.getVersionNo()));

        // 01 基本信息
        ThemeSettingsResp.Basic basic = new ThemeSettingsResp.Basic();
        basic.setThemeName(version.getThemeName());
        basic.setThemeDesc(version.getThemeDesc());
        basic.setOwnerUserId(version.getOwnerUserId() == null || version.getOwnerUserId().isBlank()
                ? OWNER_USER_ID : version.getOwnerUserId());
        basic.setPlanStartTime(version.getPlanStartTime());
        basic.setPlanEndTime(version.getPlanEndTime());
        resp.setBasic(basic);

        // 02 访问控制
        ThemeSettingsResp.Access access = new ThemeSettingsResp.Access();
        access.setTotalDrillCount(version.getTotalDrillCount());
        access.setRemainingEnterpriseCapacity(REMAINING_ENTERPRISE_CAPACITY);
        access.setPerSceneLimit(version.getPerSceneLimit());
        access.setJoinMode(version.getJoinMode());
        access.setGuestAccessEnabled(toBool(version.getGuestAccessEnabled()));
        resp.setAccess(access);

        // 03 训练策略（含防作弊占位分组）
        ThemeSettingsResp.Strategy strategy = new ThemeSettingsResp.Strategy();
        strategy.setAiResponseMode(version.getAiResponseMode());
        strategy.setTrainingModeEnabled(toBool(version.getTrainingModeEnabled()));
        strategy.setAssessmentModeEnabled(toBool(version.getAssessmentModeEnabled()));
        strategy.setCoachReviewEnabled(toBool(version.getCoachReviewEnabled()));
        strategy.setSuggestionEnabled(toBool(version.getSuggestionEnabled()));
        strategy.setPracticeMethods(splitCsv(version.getPracticeMethods()));
        strategy.setDifficulty(version.getDifficulty());
        strategy.setAllowCustomDifficulty(toBool(version.getAllowCustomDifficulty()));
        ThemeSettingsResp.AntiCheat antiCheat = new ThemeSettingsResp.AntiCheat();
        antiCheat.setFaceCheckEnabled(toBool(version.getFaceCheckEnabled()));
        antiCheat.setFaceCheckStage(version.getFaceCheckStage());
        antiCheat.setScreenSwitchMode(version.getScreenSwitchMode());
        antiCheat.setScreenSwitchLimit(version.getScreenSwitchLimit());
        strategy.setAntiCheat(antiCheat);
        resp.setStrategy(strategy);

        // 04 通过标准
        ThemeSettingsResp.PassStandard passStandard = new ThemeSettingsResp.PassStandard();
        passStandard.setScenePassType(version.getScenePassType());
        passStandard.setScenePassCount(version.getScenePassCount());
        resp.setPassStandard(passStandard);

        // 05 内容概览
        ThemeSettingsResp.ContentOverview overview = new ThemeSettingsResp.ContentOverview();
        overview.setScenarioUnitCount((int) scenarioUnitService.countUnits(themeId, version.getVersionNo()));
        overview.setCustomerProfileCount(theme.getCustomerProfileCount());
        overview.setIcebergElementCount(scenarioUnitService.countDistinctIcebergElements(themeId, version.getVersionNo()));
        overview.setEvaluationDimensionCount(scenarioUnitService.countDistinctDimensionNames(themeId, version.getVersionNo()));
        resp.setContentOverview(overview);
        return resp;
    }

    /**
     * FP-18 整体保存主题设置（事务内）：
     * 1. 主题存在且未结束校验 + 五表单字段校验
     * 2. version_no = max+1 插入新版本行（creator='管理员'）
     * 3. 复制当前版本场景单元+维度到新版本（内容不变，保证版本隔离）
     * 4. 切 current_version_no 并同步主题表冗余字段（名称/模式/难度/更新时间）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public ThemeSaveResp saveSettings(String themeId, ThemeSettingsSaveReq req) {
        ActTheme theme = requireTheme(themeId);
        if (Objects.equals(theme.getStatus(), STATUS_FINISHED)) {
            throw new BizException("主题已结束，无法保存设置");
        }
        validate(req);

        Integer currentVersionNo = theme.getCurrentVersionNo();
        int newVersionNo = maxVersionNo(themeId) + 1;

        // 2. 构造并插入新版本行（显式 setter 赋值）
        LocalDateTime now = LocalDateTime.now();
        ActThemeVersion version = buildVersionEntity(themeId, newVersionNo, req, now);
        versionMapper.insert(version);

        // 3. 复制当前版本场景单元+维度到新版本
        scenarioUnitService.copyUnitsToVersion(themeId, currentVersionNo, newVersionNo);

        // 4. 切指针 + 同步冗余字段（syncRedundantFields 内部落库并推导 themeMode/assignMode）
        themeService.syncRedundantFields(theme, version);

        ThemeSaveResp resp = new ThemeSaveResp();
        resp.setId(theme.getId());
        resp.setVersionNo(String.valueOf(newVersionNo));
        resp.setThemeMode(theme.getThemeMode());
        resp.setAssignMode(theme.getAssignMode());
        return resp;
    }

    /**
     * FP-19 版本列表：version_no 倒序（新在上，回滚不改变排序），当前版本 current=true
     */
    @Override
    public List<VersionResp> listVersions(String themeId) {
        ActTheme theme = requireTheme(themeId);
        List<ActThemeVersion> versions = versionMapper.selectList(
                new LambdaQueryWrapper<ActThemeVersion>()
                        .eq(ActThemeVersion::getThemeId, themeId)
                        .orderByDesc(ActThemeVersion::getVersionNo));
        return versions.stream().map(v -> toVersionResp(v, theme.getCurrentVersionNo())).toList();
    }

    /**
     * FP-19 回滚：仅将 current_version_no 切到目标版本并同步冗余字段，
     * 不产生新版本号、不修改任何版本记录内容；目标版本不存在或主题已结束返回 code=1
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public RollbackResp rollback(String themeId, Integer versionNo) {
        ActTheme theme = requireTheme(themeId);
        if (Objects.equals(theme.getStatus(), STATUS_FINISHED)) {
            throw new BizException("主题已结束，无法回滚");
        }
        ActThemeVersion target = requireVersion(themeId, versionNo);
        themeService.syncRedundantFields(theme, target);

        RollbackResp resp = new RollbackResp();
        resp.setId(theme.getId());
        resp.setCurrentVersionNo(String.valueOf(target.getVersionNo()));
        return resp;
    }

    /**
     * AI 路径：发布完成时按当前版本复制出下一版本（AI 路径"发布=第一版"）。
     * 与 saveSettings 的差异只有"版本内容来源"——这里照抄当前版本行而不是接收前端请求。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createVersionFromCurrent(String themeId) {
        ActTheme theme = requireTheme(themeId);
        ActThemeVersion current = requireVersion(themeId, theme.getCurrentVersionNo());
        int newVersionNo = maxVersionNo(themeId) + 1;
        LocalDateTime now = LocalDateTime.now();
        ActThemeVersion copy = copyVersionEntity(current, newVersionNo, now);
        versionMapper.insert(copy);
        scenarioUnitService.copyUnitsToVersion(themeId, current.getVersionNo(), newVersionNo);
        themeService.syncRedundantFields(theme, copy);
        log.info("AI 路径新建版本 themeId={} version={}（源自 version={}）",
                themeId, newVersionNo, current.getVersionNo());
        return newVersionNo;
    }

    /** 版本行逐字段复制（显式 setter，禁长参数列表） */
    private ActThemeVersion copyVersionEntity(ActThemeVersion source, int versionNo, LocalDateTime now) {
        ActThemeVersion copy = new ActThemeVersion();
        copy.setThemeId(source.getThemeId());
        copy.setVersionNo(versionNo);
        copy.setThemeName(source.getThemeName());
        copy.setThemeDesc(source.getThemeDesc());
        copy.setOwnerUserId(source.getOwnerUserId());
        copy.setPlanStartTime(source.getPlanStartTime());
        copy.setPlanEndTime(source.getPlanEndTime());
        copy.setTotalDrillCount(source.getTotalDrillCount());
        copy.setPerSceneLimit(source.getPerSceneLimit());
        copy.setJoinMode(source.getJoinMode());
        copy.setGuestAccessEnabled(source.getGuestAccessEnabled());
        copy.setAiResponseMode(source.getAiResponseMode());
        copy.setTrainingModeEnabled(source.getTrainingModeEnabled());
        copy.setAssessmentModeEnabled(source.getAssessmentModeEnabled());
        copy.setCoachReviewEnabled(source.getCoachReviewEnabled());
        copy.setSuggestionEnabled(source.getSuggestionEnabled());
        copy.setPracticeMethods(source.getPracticeMethods());
        copy.setDifficulty(source.getDifficulty());
        copy.setAllowCustomDifficulty(source.getAllowCustomDifficulty());
        copy.setFaceCheckEnabled(source.getFaceCheckEnabled());
        copy.setFaceCheckStage(source.getFaceCheckStage());
        copy.setScreenSwitchMode(source.getScreenSwitchMode());
        copy.setScreenSwitchLimit(source.getScreenSwitchLimit());
        copy.setScenePassType(source.getScenePassType());
        copy.setScenePassCount(source.getScenePassCount());
        copy.setCreator(source.getCreator() == null || source.getCreator().isBlank() ? CREATOR : source.getCreator());
        copy.setUsedUserCount(0);
        copy.setCompletedCount(0);
        copy.setInProgressCount(0);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        return copy;
    }

    /**
     * 服务端兜底校验（FP-18 / 03 文档 2.7）：
     * 名称非空≤50；描述≤100；总演练次数≥1；perSceneLimit 为 null 或 ≥1；
     * 演练模式至少启用一种；训练模式开启时教练点评必须开；练习方式非空
     */
    private void validate(ThemeSettingsSaveReq req) {
        if (req == null || req.getBasic() == null
                || req.getBasic().getThemeName() == null || req.getBasic().getThemeName().isBlank()) {
            throw new BizException("主题名称不能为空");
        }
        if (req.getBasic().getThemeName().length() > 50) {
            throw new BizException("主题名称不能超过50字");
        }
        if (req.getBasic().getThemeDesc() != null && req.getBasic().getThemeDesc().length() > 100) {
            throw new BizException("主题描述不能超过100字");
        }
        if (req.getAccess() == null || req.getAccess().getTotalDrillCount() == null
                || req.getAccess().getTotalDrillCount() < 1) {
            throw new BizException("总演练次数必须大于0");
        }
        if (req.getAccess().getPerSceneLimit() != null && req.getAccess().getPerSceneLimit() < 1) {
            throw new BizException("单个学员每个场景演练次数必须大于0");
        }
        ThemeSettingsSaveReq.Strategy strategy = req.getStrategy();
        boolean training = strategy != null && Boolean.TRUE.equals(strategy.getTrainingModeEnabled());
        boolean assessment = strategy != null && Boolean.TRUE.equals(strategy.getAssessmentModeEnabled());
        if (!training && !assessment) {
            throw new BizException("至少启用一种演练模式");
        }
        if (training && !Boolean.TRUE.equals(strategy.getCoachReviewEnabled())) {
            throw new BizException("训练模式开启时必须开启教练点评");
        }
        if (strategy == null || strategy.getPracticeMethods() == null || strategy.getPracticeMethods().isEmpty()) {
            throw new BizException("至少保留一种练习方式");
        }
    }

    /** 查询主题当前最大版本号（无版本时返回 0） */
    private int maxVersionNo(String themeId) {
        List<ActThemeVersion> top = versionMapper.selectList(
                new LambdaQueryWrapper<ActThemeVersion>()
                        .eq(ActThemeVersion::getThemeId, themeId)
                        .orderByDesc(ActThemeVersion::getVersionNo)
                        .last("LIMIT 1"));
        return top.isEmpty() ? 0 : top.get(0).getVersionNo();
    }

    /** 由保存请求构造新版本实体（五表单全部字段，显式 setter 赋值） */
    private ActThemeVersion buildVersionEntity(String themeId, int versionNo,
                                               ThemeSettingsSaveReq req, LocalDateTime now) {
        ActThemeVersion v = new ActThemeVersion();
        v.setThemeId(themeId);
        v.setVersionNo(versionNo);
        // 01 基本信息（ownerUserId 占位：前端不传时保持 admin）
        ThemeSettingsSaveReq.Basic basic = req.getBasic();
        v.setThemeName(basic.getThemeName());
        v.setThemeDesc(basic.getThemeDesc());
        v.setOwnerUserId(basic.getOwnerUserId() == null || basic.getOwnerUserId().isBlank()
                ? OWNER_USER_ID : basic.getOwnerUserId());
        v.setPlanStartTime(basic.getPlanStartTime());
        v.setPlanEndTime(basic.getPlanEndTime());
        // 02 访问控制
        ThemeSettingsSaveReq.Access access = req.getAccess();
        v.setTotalDrillCount(access.getTotalDrillCount());
        v.setPerSceneLimit(access.getPerSceneLimit());
        v.setJoinMode(access.getJoinMode());
        v.setGuestAccessEnabled(toInt(access.getGuestAccessEnabled()));
        // 03 训练策略 + 防作弊占位
        ThemeSettingsSaveReq.Strategy strategy = req.getStrategy();
        v.setAiResponseMode(strategy.getAiResponseMode());
        v.setTrainingModeEnabled(toInt(strategy.getTrainingModeEnabled()));
        v.setAssessmentModeEnabled(toInt(strategy.getAssessmentModeEnabled()));
        // 训练模式开启时教练点评强制置 1（业务规则兜底）
        boolean training = Boolean.TRUE.equals(strategy.getTrainingModeEnabled());
        v.setCoachReviewEnabled(training ? 1 : toInt(strategy.getCoachReviewEnabled()));
        v.setSuggestionEnabled(toInt(strategy.getSuggestionEnabled()));
        v.setPracticeMethods(String.join(",", strategy.getPracticeMethods()));
        v.setDifficulty(strategy.getDifficulty());
        v.setAllowCustomDifficulty(toInt(strategy.getAllowCustomDifficulty()));
        ThemeSettingsSaveReq.AntiCheat antiCheat = strategy.getAntiCheat();
        if (antiCheat != null) {
            v.setFaceCheckEnabled(toInt(antiCheat.getFaceCheckEnabled()));
            v.setFaceCheckStage(antiCheat.getFaceCheckStage());
            v.setScreenSwitchMode(antiCheat.getScreenSwitchMode());
            v.setScreenSwitchLimit(antiCheat.getScreenSwitchLimit());
        }
        // 04 通过标准
        ThemeSettingsSaveReq.PassStandard passStandard = req.getPassStandard();
        if (passStandard != null) {
            v.setScenePassType(passStandard.getScenePassType());
            v.setScenePassCount(passStandard.getScenePassCount());
        }
        // 版本元数据（占位统计字段归零）
        v.setCreator(CREATOR);
        v.setUsedUserCount(0);
        v.setCompletedCount(0);
        v.setInProgressCount(0);
        v.setCreatedAt(now);
        v.setUpdatedAt(now);
        return v;
    }

    /** 版本实体转响应（current 由主题指针判定） */
    private VersionResp toVersionResp(ActThemeVersion v, Integer currentVersionNo) {
        VersionResp resp = new VersionResp();
        resp.setVersionNo(String.valueOf(v.getVersionNo()));
        resp.setCurrent(Objects.equals(v.getVersionNo(), currentVersionNo));
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
                .eq(ActThemeVersion::getVersionNo, versionNo));
        if (version == null) {
            throw new BizException("目标版本不存在");
        }
        return version;
    }

    /** Integer(0/1) 转 Boolean */
    private static boolean toBool(Integer value) {
        return Objects.equals(value, 1);
    }

    /** Boolean 转 Integer(0/1) */
    private static int toInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
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
