package jack.digityhuman.atualcombattraining.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.dto.req.ThemeSettingsSaveReq;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeSettingsMapper;
import jack.digityhuman.atualcombattraining.service.impl.ThemeSettingsServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 主题全局设置服务单测（2026-10-30 版本/设置拆分后的验收口径）
 * - 保存只更新 act_theme_settings 一行，不插版本行，冗余字段同步到 act_theme
 * - 校验拦截（从旧 ThemeVersionServiceImplTest 迁移）：名称/描述/次数/模式/教练点评/练习方式
 * - D18：已发布主题训练/考核开关锁定
 * - D03/D25：训练模式开启时教练点评强制开启
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ThemeSettingsServiceImplTest {

    private static final String THEME_ID = "seed-theme-001";

    @Mock
    private ActThemeSettingsMapper settingsMapper;
    @Mock
    private ActThemeMapper themeMapper;
    /** 人脸 provider 能力（默认未配置：涉及启用人脸的用例需单独 stub） */
    @Mock
    private jack.digityhuman.atualcombattraining.service.face.FaceVerificationProvider faceVerificationProvider;

    @InjectMocks
    private ThemeSettingsServiceImpl settingsService;

    /** 库里已有的设置行（与 05 契约默认值一致） */
    private ActThemeSettings existingRow(int training, int assessment) {
        ActThemeSettings settings = new ActThemeSettings();
        settings.setThemeId(THEME_ID);
        settings.setOwnerUserId("admin");
        settings.setTotalDrillCount(3708);
        settings.setJoinMode(1);
        settings.setGuestAccessEnabled(0);
        settings.setTrainingModeEnabled(training);
        settings.setAssessmentModeEnabled(assessment);
        settings.setAiResponseMode(1);
        settings.setCoachReviewEnabled(training);
        settings.setSuggestionEnabled(0);
        settings.setDialogModes("voice,text");
        settings.setPracticeMethods("case,custom,random");
        settings.setDifficulty(2);
        settings.setAllowCustomDifficulty(1);
        settings.setFaceCheckBeforeEnabled(0);
        settings.setFaceCheckOngoingEnabled(0);
        settings.setFaceCheckIntervalSeconds(300);
        settings.setScreenSwitchMode("forbid");
        settings.setScreenSwitchLimit(0);
        settings.setScenePassType(1);
        settings.setScenePassCount(1);
        settings.setMinTrainCountToUnlockExam(1);
        settings.setMaxExamCount(0);
        return settings;
    }

    /** 合法保存请求（训练开考核关、教练点评开、三种练习方式） */
    private ThemeSettingsSaveReq validReq() {
        ThemeSettingsSaveReq req = new ThemeSettingsSaveReq();
        ThemeSettingsSaveReq.Basic basic = new ThemeSettingsSaveReq.Basic();
        basic.setThemeName("柔性智造销售话术训练");
        basic.setThemeDesc("基于六大场景知识库，训练销售精准讲解方案价值并应对客户质疑");
        req.setBasic(basic);

        ThemeSettingsSaveReq.Access access = new ThemeSettingsSaveReq.Access();
        access.setTotalDrillCount(3708);
        access.setJoinMode(1);
        access.setGuestAccessEnabled(false);
        req.setAccess(access);

        ThemeSettingsSaveReq.Strategy strategy = new ThemeSettingsSaveReq.Strategy();
        strategy.setAiResponseMode(1);
        strategy.setTrainingModeEnabled(true);
        strategy.setAssessmentModeEnabled(false);
        strategy.setCoachReviewEnabled(true);
        strategy.setSuggestionEnabled(false);
        strategy.setPracticeMethods(List.of("case", "custom", "random"));
        strategy.setDifficulty(2);
        strategy.setAllowCustomDifficulty(true);
        req.setStrategy(strategy);

        ThemeSettingsSaveReq.PassStandard passStandard = new ThemeSettingsSaveReq.PassStandard();
        passStandard.setScenePassType(1);
        passStandard.setScenePassCount(1);
        req.setPassStandard(passStandard);
        return req;
    }

    private void stubExisting(ActThemeSettings row) {
        when(settingsMapper.selectById(THEME_ID)).thenReturn(row);
        when(settingsMapper.update(any(ActThemeSettings.class), any())).thenReturn(1);
    }

    @Test
    void 保存设置_更新设置行且不插版本() {
        stubExisting(existingRow(1, 0));

        ThemeSettingsService.SaveResult result = settingsService.save(
                THEME_ID, validReq(), "柔性智造销售话术训练", "描述", 2);

        // 推导冗余：训练开+考核关=1 日常训练；joinMode=1 → assignMode=1
        assertEquals(1, result.themeMode());
        assertEquals(1, result.assignMode());
        assertEquals(2, result.difficulty());
        // 只更新设置行，不再插入版本行（版本行属于 ContentVersionService 职责）
        verify(settingsMapper).update(any(ActThemeSettings.class), any());
    }

    @Test
    void 保存设置_冗余字段同步到主题表() {
        stubExisting(existingRow(1, 0));

        settingsService.save(THEME_ID, validReq(), "新主题名", "新描述", 2);

        ArgumentCaptor<ActTheme> captor = ArgumentCaptor.forClass(ActTheme.class);
        verify(themeMapper).updateById(captor.capture());
        assertEquals("新主题名", captor.getValue().getThemeName());
        assertEquals("新描述", captor.getValue().getThemeDesc());
        assertEquals(1, captor.getValue().getThemeMode());
        assertEquals(2, captor.getValue().getDifficulty());
    }

    @Test
    void 保存设置_训练模式开教练点评关_拒绝() {
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setCoachReviewEnabled(false);

        BizException e = assertThrows(BizException.class,
                () -> settingsService.save(THEME_ID, req, "名", "述", 2));
        assertEquals("训练模式开启时必须开启教练点评", e.getMessage());
    }

    @Test
    void 保存设置_两种演练模式都关_拒绝() {
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setTrainingModeEnabled(false);
        req.getStrategy().setAssessmentModeEnabled(false);

        BizException e = assertThrows(BizException.class,
                () -> settingsService.save(THEME_ID, req, "名", "述", 2));
        assertEquals("至少启用一种演练模式", e.getMessage());
    }

    @Test
    void 保存设置_名称为空_拒绝() {
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getBasic().setThemeName("  ");

        BizException e = assertThrows(BizException.class,
                () -> settingsService.save(THEME_ID, req, "  ", "述", 2));
        assertEquals("主题名称不能为空", e.getMessage());
    }

    @Test
    void 保存设置_名称超50字_拒绝() {
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getBasic().setThemeName("超".repeat(51));

        BizException e = assertThrows(BizException.class,
                () -> settingsService.save(THEME_ID, req, req.getBasic().getThemeName(), "述", 2));
        assertEquals("主题名称不能超过50字", e.getMessage());
    }

    @Test
    void 保存设置_练习方式为空_拒绝() {
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setPracticeMethods(List.of());

        BizException e = assertThrows(BizException.class,
                () -> settingsService.save(THEME_ID, req, "名", "述", 2));
        assertEquals("至少保留一种练习方式", e.getMessage());
    }

    @Test
    void 已发布主题_模式开关变更_拒绝() {
        // D18：首次发布后训练/考核开关锁定
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setAssessmentModeEnabled(true);

        BizException e = assertThrows(BizException.class,
                () -> settingsService.save(THEME_ID, req, "名", "述", 2));
        assertEquals("主题发布后训练/考核模式不可修改（只影响新 attempt 的设置除外）", e.getMessage());
    }

    @Test
    void 未发布主题_模式开关可改() {
        stubExisting(existingRow(1, 0));
        ThemeSettingsSaveReq req = validReq();
        req.getStrategy().setAssessmentModeEnabled(true);
        // 训练仍需开启，教练点评强制开
        req.getStrategy().setCoachReviewEnabled(true);

        ThemeSettingsService.SaveResult result = settingsService.save(
                THEME_ID, req, "名", "述", null);

        assertEquals(3, result.themeMode());
    }

    @Test
    void 设置行缺失时按默认值补建() {
        when(settingsMapper.selectById(THEME_ID)).thenReturn(null);

        ActThemeSettings settings = settingsService.require(THEME_ID);

        assertEquals(1, settings.getTrainingModeEnabled());
        assertEquals("case,custom,random", settings.getPracticeMethods());
        verify(settingsMapper).insert(any(ActThemeSettings.class));
    }

    @Test
    void 设置行缺失时保存先补建再更新() {
        when(settingsMapper.selectById(THEME_ID)).thenReturn(null);
        when(settingsMapper.update(any(ActThemeSettings.class), any())).thenReturn(1);

        // require 兜底插入默认行后，save 内部的 update 正常执行
        ThemeSettingsService.SaveResult result = settingsService.save(
                THEME_ID, validReq(), "名", "述", null);

        assertEquals(1, result.themeMode());
        verify(settingsMapper).insert(any(ActThemeSettings.class));
        verify(settingsMapper).update(any(ActThemeSettings.class), any());
    }
}

