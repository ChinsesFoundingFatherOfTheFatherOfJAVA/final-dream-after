package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeSettingsMapper;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 主题全局设置服务实现
 * <p>
 * 关键差异（对比旧 ThemeVersionServiceImpl.saveSettings）：
 * 保存设置不再插入版本行、不再复制场景、不再切 current_version_no；
 * 只更新 act_theme_settings 一行，并把名称/模式/难度等冗余字段同步到 act_theme。
 * <p>
 * 锁定规则（D18）：published_version_no 非空（已发布）后，训练/考核开关不可修改；
 * 其余字段可改，但只影响之后创建的 attempt（D08，attempt 用自己的设置快照）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThemeSettingsServiceImpl implements ThemeSettingsService {

    private final ActThemeSettingsMapper settingsMapper;
    /** 人脸 provider 能力（未配置时禁止启用人脸校验，契约 03 §12.2） */
    private final jack.digityhuman.atualcombattraining.service.face.FaceVerificationProvider faceVerificationProvider;
    private final ActThemeMapper themeMapper;

    @Override
    public ActThemeSettings require(String themeId) {
        ActThemeSettings settings = settingsMapper.selectById(themeId);
        if (settings != null) {
            return settings;
        }
        // 迁移漏网兜底：按契约默认值落一行（不阻塞读取）
        ActThemeSettings created = new ActThemeSettings();
        created.setThemeId(themeId);
        created.setTotalDrillCount(0);
        created.setJoinMode(1);
        created.setGuestAccessEnabled(0);
        created.setTrainingModeEnabled(1);
        created.setAssessmentModeEnabled(0);
        created.setAiResponseMode(1);
        created.setCoachReviewEnabled(1);
        created.setSuggestionEnabled(0);
        created.setDialogModes("voice,text");
        created.setPracticeMethods("case,custom,random");
        created.setDifficulty(2);
        created.setAllowCustomDifficulty(1);
        created.setFaceCheckBeforeEnabled(0);
        created.setFaceCheckOngoingEnabled(0);
        created.setFaceCheckIntervalSeconds(300);
        created.setScreenSwitchMode("forbid");
        created.setScreenSwitchLimit(0);
        created.setScenePassType(1);
        created.setScenePassCount(1);
        created.setMinTrainCountToUnlockExam(1);
        created.setMaxExamCount(0);
        settingsMapper.insert(created);
        log.warn("主题缺少全局设置行，已按默认值补建 themeId={}", themeId);
        return created;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SaveResult save(String themeId, ThemeSettingsSaveReq req, String themeName, String themeDesc,
                           Integer publishedVersionNo) {
        boolean published = publishedVersionNo != null;
        validate(req, published);
        // D18：首次发布后训练/考核开关锁定——传值与现值不一致直接拒绝
        if (published && req.getStrategy() != null) {
            ActThemeSettings current = require(themeId);
            boolean reqTraining = Boolean.TRUE.equals(req.getStrategy().getTrainingModeEnabled());
            boolean reqAssessment = Boolean.TRUE.equals(req.getStrategy().getAssessmentModeEnabled());
            boolean curTraining = Objects.equals(current.getTrainingModeEnabled(), 1);
            boolean curAssessment = Objects.equals(current.getAssessmentModeEnabled(), 1);
            if (reqTraining != curTraining || reqAssessment != curAssessment) {
                throw new BizException("主题发布后训练/考核模式不可修改（只影响新 attempt 的设置除外）");
            }
        }

        ActThemeSettings settings = require(themeId);
        applyRequest(settings, req);

        ActThemeSettings update = new ActThemeSettings();
        update.setThemeId(themeId);
        update.setOwnerUserId(settings.getOwnerUserId());
        update.setPlanStartTime(settings.getPlanStartTime());
        update.setPlanEndTime(settings.getPlanEndTime());
        update.setTotalDrillCount(settings.getTotalDrillCount());
        update.setPerSceneLimit(settings.getPerSceneLimit());
        update.setJoinMode(settings.getJoinMode());
        update.setGuestAccessEnabled(settings.getGuestAccessEnabled());
        update.setTrainingModeEnabled(settings.getTrainingModeEnabled());
        update.setAssessmentModeEnabled(settings.getAssessmentModeEnabled());
        update.setAiResponseMode(settings.getAiResponseMode());
        update.setCoachReviewEnabled(settings.getCoachReviewEnabled());
        update.setSuggestionEnabled(settings.getSuggestionEnabled());
        update.setDialogModes(settings.getDialogModes());
        update.setPracticeMethods(settings.getPracticeMethods());
        update.setDifficulty(settings.getDifficulty());
        update.setAllowCustomDifficulty(settings.getAllowCustomDifficulty());
        update.setFaceCheckBeforeEnabled(settings.getFaceCheckBeforeEnabled());
        update.setFaceCheckOngoingEnabled(settings.getFaceCheckOngoingEnabled());
        update.setFaceCheckIntervalSeconds(settings.getFaceCheckIntervalSeconds());
        update.setScreenSwitchMode(settings.getScreenSwitchMode());
        update.setScreenSwitchLimit(settings.getScreenSwitchLimit());
        update.setScenePassType(settings.getScenePassType());
        update.setScenePassCount(settings.getScenePassCount());
        update.setMinTrainCountToUnlockExam(settings.getMinTrainCountToUnlockExam());
        update.setMaxExamCount(settings.getMaxExamCount());
        int updated = settingsMapper.update(update, new LambdaUpdateWrapper<ActThemeSettings>()
                .eq(ActThemeSettings::getThemeId, themeId));
        if (updated == 0) {
            settingsMapper.insert(update);
        }

        // 冗余字段同步到 act_theme（不碰任何版本指针）
        Integer themeMode = ThemeServiceImpl.deriveThemeMode(
                settings.getTrainingModeEnabled(), settings.getAssessmentModeEnabled());
        Integer assignMode = settings.getJoinMode() != null && settings.getJoinMode() == 2 ? 2 : 1;
        ActTheme themeUpdate = new ActTheme();
        themeUpdate.setId(themeId);
        themeUpdate.setThemeName(themeName);
        themeUpdate.setThemeDesc(themeDesc);
        themeUpdate.setThemeMode(themeMode);
        themeUpdate.setAssignMode(assignMode);
        themeUpdate.setDifficulty(settings.getDifficulty());
        themeUpdate.setUpdatedAt(LocalDateTime.now());
        themeMapper.updateById(themeUpdate);
        log.info("主题设置已保存 themeId={} themeMode={}（不产生内容版本）", themeId, themeMode);
        return new SaveResult(themeMode, assignMode, settings.getDifficulty());
    }

    /** 请求值覆盖到设置实体（不改指针、不碰未提交字段） */
    private void applyRequest(ActThemeSettings settings, ThemeSettingsSaveReq req) {
        ThemeSettingsSaveReq.Basic basic = req.getBasic();
        if (basic != null) {
            if (basic.getOwnerUserId() != null && !basic.getOwnerUserId().isBlank()) {
                settings.setOwnerUserId(basic.getOwnerUserId());
            }
            settings.setPlanStartTime(basic.getPlanStartTime());
            settings.setPlanEndTime(basic.getPlanEndTime());
        }
        ThemeSettingsSaveReq.Access access = req.getAccess();
        if (access != null) {
            settings.setTotalDrillCount(access.getTotalDrillCount());
            settings.setPerSceneLimit(access.getPerSceneLimit());
            settings.setJoinMode(access.getJoinMode());
            settings.setGuestAccessEnabled(toInt(access.getGuestAccessEnabled()));
        }
        ThemeSettingsSaveReq.Strategy strategy = req.getStrategy();
        if (strategy != null) {
            settings.setAiResponseMode(strategy.getAiResponseMode());
            settings.setTrainingModeEnabled(toInt(strategy.getTrainingModeEnabled()));
            settings.setAssessmentModeEnabled(toInt(strategy.getAssessmentModeEnabled()));
            // 训练模式开启时教练点评强制开启（业务规则兜底）
            boolean training = Boolean.TRUE.equals(strategy.getTrainingModeEnabled());
            settings.setCoachReviewEnabled(training ? 1 : toInt(strategy.getCoachReviewEnabled()));
            settings.setSuggestionEnabled(toInt(strategy.getSuggestionEnabled()));
            List<String> methods = strategy.getPracticeMethods();
            settings.setPracticeMethods(methods == null || methods.isEmpty()
                    ? "case,custom,random" : String.join(",", methods));
            settings.setDifficulty(strategy.getDifficulty());
            settings.setAllowCustomDifficulty(toInt(strategy.getAllowCustomDifficulty()));
            ThemeSettingsSaveReq.AntiCheat antiCheat = strategy.getAntiCheat();
            if (antiCheat != null) {
                // 契约 03 §12.2：provider 未配置时主题不允许启用人脸校验（避免假防作弊）
                boolean faceRequested = Boolean.TRUE.equals(antiCheat.getFaceCheckEnabled());
                if (faceRequested && !faceVerificationProvider.configured()) {
                    throw new BizException("人脸核验服务未配置，无法启用人脸校验");
                }
                // 旧单值 faceCheckStage 映射到两个开关；faceCheckStage=before 时 ongoing 置 0
                Boolean face = antiCheat.getFaceCheckEnabled();
                settings.setFaceCheckBeforeEnabled(toInt(face));
                settings.setFaceCheckOngoingEnabled(
                        "ongoing".equals(antiCheat.getFaceCheckStage()) ? toInt(face) : 0);
                settings.setScreenSwitchMode(antiCheat.getScreenSwitchMode());
                settings.setScreenSwitchLimit(antiCheat.getScreenSwitchLimit());
            }
        }
        ThemeSettingsSaveReq.PassStandard passStandard = req.getPassStandard();
        if (passStandard != null) {
            settings.setScenePassType(passStandard.getScenePassType());
            settings.setScenePassCount(passStandard.getScenePassCount());
        }
    }

    /**
     * 服务端兜底校验。与旧实现的差异：
     * 训练/考核开关在首次发布后锁定（D18）——传值与库里的现值不一致即拒绝。
     */
    private void validate(ThemeSettingsSaveReq req, boolean published) {
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
        if (req.getPassStandard() != null && req.getPassStandard().getScenePassCount() != null
                && req.getPassStandard().getScenePassCount() < 1) {
            throw new BizException("场景通过次数必须大于0");
        }
    }

    private static int toInt(Boolean value) {
        return Boolean.TRUE.equals(value) ? 1 : 0;
    }
}


