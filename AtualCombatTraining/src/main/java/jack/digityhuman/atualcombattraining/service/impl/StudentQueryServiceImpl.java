package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.dto.resp.RandomScriptResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHistoryResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHomeResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentHomeSummaryResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentPracticeRecordsResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentScenarioDetailResp;
import jack.digityhuman.atualcombattraining.dto.resp.StudentThemeDetailResp;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActCaseScript;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActDrillScript;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActPracticeReport;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActCaseScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActDrillScriptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeReportMapper;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeSettingsMapper;
import jack.digityhuman.atualcombattraining.service.AttemptQuotaService;
import jack.digityhuman.atualcombattraining.service.RandomScriptService;
import jack.digityhuman.atualcombattraining.service.StudentQueryService;
import jack.digityhuman.atualcombattraining.service.ThemeSettingsService;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.List;
import java.util.Map;

/**
 * 学员端只读查询实现
 * <p>
 * 组装策略（不跨表 JOIN）：
 * <ul>
 *   <li>home：参与关系 → 主题批量查 → 设置批量查 → 我的统计（按主题聚合 formal_count / passed 场景数 / 考核余量）；</li>
 *   <li>themeDetail：主题 + 设置 + 已发布场景 + 材料快照 + 每场景我的统计；</li>
 *   <li>history：attempt 分页 → 报告批量查（attempt in）→ 主题/场景名批量补齐。</li>
 * </ul>
 * 铁律：所有查询都以 user 维度过滤，防止越权读他人数据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StudentQueryServiceImpl implements StudentQueryService {

    private static final String PARTICIPANT_ACTIVE = "active";
    /** 学员可见材料 */
    private static final String VISIBILITY_STUDENT = "student";
    private static final int MODE_TRAINING = 1;
    /** 首页"更多学习机会"条数（契约 02 §5.2：limit=6） */
    private static final int OPPORTUNITY_LIMIT = 6;
    private static final int MODE_EXAM = 2;
    /** 加入方式：2=完全公开直接加入（契约 D02） */
    private static final int JOIN_MODE_PUBLIC = 2;
    /** 练习历程"练习中"Tab 的 attempt 状态集合（未产生最终报告的中间态） */
    private static final List<String> IN_PROGRESS_STATUSES =
            List.of("preparing", "ready", "in_progress", "report_generating");

    private final ActThemeParticipantMapper participantMapper;
    private final ActThemeMapper themeMapper;
    private final ThemeSettingsService themeSettingsService;
    private final ActScenarioUnitMapper unitMapper;
    private final ActContentVersionMaterialMapper materialMapper;
    private final ActPracticeAttemptMapper attemptMapper;
    private final ActPracticeReportMapper reportMapper;
    private final ActThemeSettingsMapper settingsMapper;
    private final ActEvaluationDimensionMapper dimensionMapper;
    private final ActProductProfileMapper productProfileMapper;
    private final ActScenarioProductProfileMapper scenarioProductProfileMapper;
    private final ActCaseScriptMapper caseScriptMapper;
    private final ActDrillScriptMapper drillScriptMapper;
    private final AttemptQuotaService quotaService;
    private final RandomScriptService randomScriptService;

    /** 主题状态：已发布（与 AttemptPreflightServiceImpl 同口径） */
    private static final int THEME_PUBLISHED = 3;

    /** 详情页"我的训练记录"条数（契约 D09：详情抽屉 10 条） */
    private static final int DETAIL_RECORD_LIMIT = 10;
    /** 场景卡展示的适用人群标签数（契约 F07：前两个） */
    private static final int AUDIENCE_TAG_LIMIT = 2;

    @Override
    public StudentHomeResp home(String userId) {
        List<ActThemeParticipant> participations = participantMapper.selectList(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getUserId, userId)
                        .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE)
                        .orderByDesc(ActThemeParticipant::getJoinedAt));

        StudentHomeResp resp = new StudentHomeResp();
        resp.setThemes(participations.stream().map(participation -> {
            ActTheme theme = themeMapper.selectById(participation.getThemeId());
            if (theme == null) {
                return null;
            }
            ActThemeSettings settings = themeSettingsService.require(theme.getId());
            StudentHomeResp.Card card = new StudentHomeResp.Card();
            card.setThemeId(theme.getId());
            card.setName(theme.getThemeName());
            card.setDescription(theme.getThemeDesc());
            card.setTrainingModeEnabled(settings.getTrainingModeEnabled());
            card.setAssessmentModeEnabled(settings.getAssessmentModeEnabled());
            card.setPracticeMethods(settings.getPracticeMethods());
            card.setPlanStartTime(settings.getPlanStartTime());
            card.setPlanEndTime(settings.getPlanEndTime());
            card.setPublishedVersionNo(theme.getPublishedVersionNo());
            // 我的统计摘要
            card.setMyTrainingCount(quotaService.countFormalAttemptsByMode(
                    userId, theme.getId(), MODE_TRAINING));
            long passed = countPassedScenarios(userId, theme.getId(), MODE_TRAINING);
            long scenarioCount = countPublishedScenarios(theme);
            card.setMyPassedScenarios(passed);
            card.setScenarioCount(scenarioCount);
            card.setProgress(scenarioCount <= 0
                    ? 0 : (int) Math.min(100, passed * 100 / scenarioCount));
            card.setStatus(resolveCardStatus(
                    settings.getPlanEndTime(), passed, scenarioCount, card.getMyTrainingCount()));
            card.setMyExamRemaining(examRemaining(userId, theme, settings, participation));
            return card;
        }).filter(card -> card != null).toList());
        return resp;
    }

    @Override
    public List<StudentHomeResp.Card> opportunities(String userId, int limit) {
        // AC-03：公开加入（join_mode=2，D02）+ 已发布 + 计划期内 + 我未参加
        LocalDateTime now = LocalDateTime.now();
        List<ActThemeSettings> publicSettings = settingsMapper.selectList(
                new LambdaQueryWrapper<ActThemeSettings>()
                        .eq(ActThemeSettings::getJoinMode, 2)
                        .and(wrapper -> wrapper.isNull(ActThemeSettings::getPlanEndTime)
                                .or().ge(ActThemeSettings::getPlanEndTime, now))
                        .and(wrapper -> wrapper.isNull(ActThemeSettings::getPlanStartTime)
                                .or().le(ActThemeSettings::getPlanStartTime, now))
                        .orderByAsc(ActThemeSettings::getPlanEndTime)
                        .last("LIMIT " + Math.max(1, limit) * 3));
        List<StudentHomeResp.Card> cards = new java.util.ArrayList<>();
        for (ActThemeSettings settings : publicSettings) {
            if (cards.size() >= Math.max(1, limit)) {
                break;
            }
            ActTheme theme = themeMapper.selectById(settings.getThemeId());
            if (theme == null || theme.getPublishedVersionNo() == null
                    || !Objects.equals(theme.getStatus(), THEME_PUBLISHED)) {
                continue;
            }
            // 未参加：无 active 参与关系（removed 的允许重新出现）
            Long joined = participantMapper.selectCount(
                    new LambdaQueryWrapper<ActThemeParticipant>()
                            .eq(ActThemeParticipant::getThemeId, settings.getThemeId())
                            .eq(ActThemeParticipant::getUserId, userId)
                            .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE));
            if (joined != null && joined > 0) {
                continue;
            }
            StudentHomeResp.Card card = new StudentHomeResp.Card();
            card.setThemeId(theme.getId());
            card.setName(theme.getThemeName());
            card.setDescription(theme.getThemeDesc());
            card.setTrainingModeEnabled(settings.getTrainingModeEnabled());
            card.setAssessmentModeEnabled(settings.getAssessmentModeEnabled());
            card.setPracticeMethods(settings.getPracticeMethods());
            card.setPlanStartTime(settings.getPlanStartTime());
            card.setPlanEndTime(settings.getPlanEndTime());
            card.setPublishedVersionNo(theme.getPublishedVersionNo());
            card.setMyTrainingCount(0);
            card.setMyPassedScenarios(0);
            card.setMyExamRemaining(settings.getMaxExamCount() == null || settings.getMaxExamCount() <= 0
                    ? -1 : settings.getMaxExamCount());
            cards.add(card);
        }
        return cards;
    }

    @Override
    public void joinTheme(String userId, String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        // 契约 API-S03 失败枚举：THEME_NOT_PUBLISHED / PLAN_NOT_STARTED / PLAN_ENDED / THEME_ENDED
        if (theme.getPublishedVersionNo() == null || !Objects.equals(theme.getStatus(), THEME_PUBLISHED)) {
            throw new BizException("主题尚未发布");
        }
        ActThemeSettings settings = themeSettingsService.require(themeId);
        if (!Objects.equals(settings.getJoinMode(), JOIN_MODE_PUBLIC)) {
            throw new BizException("该主题不支持直接加入，请联系管理员分配");
        }
        LocalDateTime now = LocalDateTime.now();
        if (settings.getPlanStartTime() != null && now.isBefore(settings.getPlanStartTime())) {
            throw new BizException("主题计划尚未开始");
        }
        if (settings.getPlanEndTime() != null && now.isAfter(settings.getPlanEndTime())) {
            throw new BizException("主题计划已结束");
        }
        // 幂等：已有 active 关系直接返回，不重复插入（唯一键 theme_id+user_id 也兜底）
        ActThemeParticipant existing = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getUserId, userId)
                        .last("LIMIT 1"));
        if (existing != null && PARTICIPANT_ACTIVE.equals(existing.getStatus())) {
            return;
        }
        if (existing != null) {
            // 曾被移出：恢复 active，保留原 id 与历史 attempt
            ActThemeParticipant update = new ActThemeParticipant();
            update.setId(existing.getId());
            update.setStatus(PARTICIPANT_ACTIVE);
            update.setRemovedAt(null);
            update.setUpdatedAt(now);
            participantMapper.updateById(update);
            return;
        }
        ActThemeParticipant participant = new ActThemeParticipant();
        participant.setThemeId(themeId);
        participant.setUserId(userId);
        participant.setSource("public");
        participant.setStatus(PARTICIPANT_ACTIVE);
        participant.setExtraExamAttemptCount(0);
        participant.setJoinedAt(now);
        participant.setUpdatedAt(now);
        participantMapper.insert(participant);
    }

    @Override
    public StudentThemeDetailResp themeDetail(String userId, String themeId) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        ActThemeParticipant participation = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getUserId, userId)
                        .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE)
                        .last("LIMIT 1"));
        if (participation == null) {
            throw new BizException("你不在该主题的学员名单中");
        }
        ActThemeSettings settings = themeSettingsService.require(themeId);
        Integer version = theme.getPublishedVersionNo();
        if (version == null) {
            throw new BizException("主题尚未发布");
        }

        StudentThemeDetailResp resp = new StudentThemeDetailResp();
        resp.setThemeId(theme.getId());
        resp.setName(theme.getThemeName());
        resp.setDescription(theme.getThemeDesc());
        resp.setPublishedVersionNo(version);
        resp.setTrainingModeEnabled(settings.getTrainingModeEnabled());
        resp.setAssessmentModeEnabled(settings.getAssessmentModeEnabled());
        resp.setPracticeMethods(settings.getPracticeMethods());
        resp.setDifficulty(settings.getDifficulty());
        resp.setAllowCustomDifficulty(settings.getAllowCustomDifficulty());
        resp.setPlanStartTime(settings.getPlanStartTime());
        resp.setPlanEndTime(settings.getPlanEndTime());

        // 已发布版本场景（含维度数供前端展示）
        List<ActScenarioUnit> units = unitMapper.selectList(
                new LambdaQueryWrapper<ActScenarioUnit>()
                        .eq(ActScenarioUnit::getThemeId, themeId)
                        .eq(ActScenarioUnit::getVersionNo, version)
                        .orderByAsc(ActScenarioUnit::getSortOrder));

        // 计划期外 / 主题暂停时页面仍可查看，只是不能开始新 attempt（契约 F07）
        String stopReason = resolveStopReason(theme, settings);
        boolean startable = stopReason == null;

        List<StudentThemeDetailResp.Scenario> scenarios = new ArrayList<>();
        for (ActScenarioUnit unit : units) {
            StudentThemeDetailResp.Scenario scenario = new StudentThemeDetailResp.Scenario();
            scenario.setScenarioId(unit.getId());
            scenario.setName(unit.getUnitName());
            scenario.setSortOrder(unit.getSortOrder());
            scenario.setAudience(splitAudience(unit.getAudience()));
            scenario.setBusinessGoal(unit.getBusinessGoal());
            scenario.setTimeLimitMinutes(unit.getTimeLimitMinutes());
            scenario.setPassScore(unit.getPassScore());
            // AC-04：维度摘要（名称+权重），供详情页展示评分口径
            scenario.setDimensions(dimensionMapper.selectList(
                            new LambdaQueryWrapper<ActEvaluationDimension>()
                                    .eq(ActEvaluationDimension::getScenarioUnitId, unit.getId())
                                    .orderByAsc(ActEvaluationDimension::getSortOrder))
                    .stream().map(dimension -> {
                        StudentThemeDetailResp.DimensionBrief brief =
                                new StudentThemeDetailResp.DimensionBrief();
                        brief.setName(dimension.getDimensionName());
                        brief.setWeight(dimension.getWeight());
                        return brief;
                    }).toList());
            scenario.setMyTrainingCount(quotaService.countFormalAttempts(
                    userId, themeId, unit.getId(), MODE_TRAINING));
            scenario.setMyExamCount(quotaService.countFormalAttempts(
                    userId, themeId, unit.getId(), MODE_EXAM));
            scenario.setAttemptCount(scenario.getMyTrainingCount() + scenario.getMyExamCount());
            fillPassedAndHighest(userId, themeId, unit.getId(), scenario);

            // 状态与下一个动作（契约 F07：开始练习 / 继续练习 / 再练一次）
            boolean passed = scenario.isMyTrainingPassed() || scenario.isMyExamPassed();
            String status = passed ? "passed"
                    : (scenario.getAttemptCount() > 0 ? "in_progress" : "not_started");
            scenario.setStatus(status);
            scenario.setStatusText(switch (status) {
                case "passed" -> "已通过";
                case "in_progress" -> "进行中";
                default -> "未开始";
            });
            String nextAction = "passed".equals(status) || "in_progress".equals(status)
                    ? "retry" : "start";
            scenario.setNextAction(nextAction);
            scenario.setNextActionText(switch (nextAction) {
                case "retry" -> "再练一次";
                default -> "开始练习";
            });

            // 训练/考核动作可用性：不可用一定给出中文原因（前端直接展示，不自己编理由）
            scenario.setTrainingAction(trainingAction(settings, startable, stopReason));
            scenario.setExamAction(examAction(settings, startable, stopReason, userId, themeId));
            scenarios.add(scenario);
        }
        resp.setScenarios(scenarios);

        // 页头分组（契约 04 §API-S04 theme）
        long scenarioCount = scenarios.size();
        long passedScenarioCount = scenarios.stream()
                .filter(s -> "passed".equals(s.getStatus())).count();
        StudentThemeDetailResp.Theme themeGroup = new StudentThemeDetailResp.Theme();
        themeGroup.setId(theme.getId());
        themeGroup.setName(theme.getThemeName());
        themeGroup.setDescription(theme.getThemeDesc());
        themeGroup.setMode(ThemeServiceImpl.deriveThemeMode(
                settings.getTrainingModeEnabled(), settings.getAssessmentModeEnabled()));
        themeGroup.setModeText(switch (themeGroup.getMode()) {
            case 1 -> "日常训练";
            case 2 -> "通关考核";
            default -> "训考一体";
        });
        themeGroup.setDifficulty(settings.getDifficulty());
        themeGroup.setDifficultyText(difficultyText(settings.getDifficulty()));
        themeGroup.setScenarioCount(scenarioCount);
        themeGroup.setPassedScenarioCount(passedScenarioCount);
        themeGroup.setProgress(scenarioCount <= 0
                ? 0 : (int) Math.min(100, passedScenarioCount * 100 / scenarioCount));
        themeGroup.setExamUnlocked(quotaService.examUnlocked(userId, themeId,
                settings.getMinTrainCountToUnlockExam() == null
                        ? 0 : settings.getMinTrainCountToUnlockExam()));
        themeGroup.setPlanStartTime(settings.getPlanStartTime());
        themeGroup.setPlanEndTime(settings.getPlanEndTime());
        themeGroup.setPublishedVersionNo(version);
        themeGroup.setStartable(startable);
        themeGroup.setStopReason(stopReason);
        resp.setTheme(themeGroup);

        // 学习策略（act_theme_settings，不进版本）
        StudentThemeDetailResp.StudyPolicy policy = new StudentThemeDetailResp.StudyPolicy();
        policy.setTrainingEnabled(Objects.equals(settings.getTrainingModeEnabled(), 1));
        policy.setExamEnabled(Objects.equals(settings.getAssessmentModeEnabled(), 1));
        policy.setDialogModes(splitCsv(settings.getDialogModes()));
        policy.setPracticeMethods(splitCsv(settings.getPracticeMethods()));
        policy.setAllowCustomDifficulty(Objects.equals(settings.getAllowCustomDifficulty(), 1));
        policy.setMinTrainCountToUnlockExam(settings.getMinTrainCountToUnlockExam());
        policy.setMaxExamCount(settings.getMaxExamCount());
        resp.setStudyPolicy(policy);

        // 评估标准：通过分取各场景最小值（最严格口径），维度名按场景顺序去重
        StudentThemeDetailResp.Criteria criteria = new StudentThemeDetailResp.Criteria();
        criteria.setPassScore(scenarios.stream()
                .map(StudentThemeDetailResp.Scenario::getPassScore)
                .filter(Objects::nonNull)
                .min(Integer::compareTo)
                .orElse(null));
        Map<String, StudentThemeDetailResp.DimensionBrief> dimensionIndex = new LinkedHashMap<>();
        for (StudentThemeDetailResp.Scenario scenario : scenarios) {
            if (scenario.getDimensions() == null) {
                continue;
            }
            for (StudentThemeDetailResp.DimensionBrief brief : scenario.getDimensions()) {
                dimensionIndex.putIfAbsent(brief.getName(), brief);
            }
        }
        criteria.setDimensions(new ArrayList<>(dimensionIndex.values()));
        // 无场景时不出评估标准卡（前端按空列表展示空态，不造假数据）
        resp.setEvaluationCriteria(scenarioCount <= 0 ? List.of() : List.of(criteria));

        // 学员可见材料（发布快照，D29 本地渲染回退）
        List<ActContentVersionMaterial> materials = materialMapper.selectList(
                new LambdaQueryWrapper<ActContentVersionMaterial>()
                        .eq(ActContentVersionMaterial::getThemeId, themeId)
                        .eq(ActContentVersionMaterial::getContentVersionNo, version)
                        .eq(ActContentVersionMaterial::getVisibility, VISIBILITY_STUDENT)
                        .orderByAsc(ActContentVersionMaterial::getSortOrder));
        resp.setMaterials(materials.stream().map(material -> {
            StudentThemeDetailResp.Material item = new StudentThemeDetailResp.Material();
            item.setMaterialId(material.getMaterialId());
            item.setName(material.getName());
            item.setFileType(material.getFileType());
            item.setRenderMode(material.getRenderMode());
            item.setSourceType(material.getSourceType());
            item.setSourceId(material.getSourceId());
            item.setPreviewFileId(material.getPreviewFileId());
            item.setTypeText(materialTypeText(material));
            return item;
        }).toList());

        // 产品档案（发布快照；无档案就是空列表，前端展示空态，不造假数据）
        resp.setProductProfiles(loadProductProfiles(themeId, version));
        // 我的训练记录（D09：详情抽屉 10 条）
        resp.setRecentRecords(loadRecentRecords(userId, themeId));
        return resp;
    }

    /**
     * 场景准备详情（契约 04 §API-S05 / 01 §F09）。
     * <p>
     * 组装顺序按"资格 → 场景 → 我的统计 → 案例/档案/维度 → 可用练习方式"：
     * 先做参与关系与发布版本校验（与 {@link #themeDetail} 同一口径，越权一律拒绝），
     * 再一次性把弹窗需要的九类信息取全，避免前端串行发多个请求。
     * <p>
     * 只读：本方法不写任何表，也不触发 attempt 创建。
     */
    @Override
    public StudentScenarioDetailResp scenarioDetail(String userId, String themeId, String scenarioId) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        // 鉴权口径与 themeDetail 一致：必须是该主题的有效参与学员
        ActThemeParticipant participation = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getUserId, userId)
                        .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE)
                        .last("LIMIT 1"));
        if (participation == null) {
            throw new BizException("你不在该主题的学员名单中");
        }
        Integer version = theme.getPublishedVersionNo();
        if (version == null) {
            throw new BizException("主题尚未发布");
        }
        // 场景必须属于当前发布版本：旧版本的单元行（含回滚前的内容）一律视为已下线
        ActScenarioUnit unit = unitMapper.selectOne(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, version)
                .eq(ActScenarioUnit::getId, scenarioId)
                .last("LIMIT 1"));
        if (unit == null) {
            throw new BizException("场景不存在或已下线");
        }
        ActThemeSettings settings = themeSettingsService.require(themeId);

        StudentScenarioDetailResp resp = new StudentScenarioDetailResp();
        resp.setThemeId(theme.getId());
        resp.setThemeName(theme.getThemeName());
        resp.setScenarioId(unit.getId());
        resp.setSortOrder(unit.getSortOrder());
        resp.setName(unit.getUnitName());
        resp.setTimeLimitMinutes(unit.getTimeLimitMinutes());
        // 与 preflight.maxDurationSeconds 同口径：限时（分钟）×60，缺省 900 秒（15 分钟）
        resp.setMaxDurationSeconds(unit.getTimeLimitMinutes() != null
                ? unit.getTimeLimitMinutes() * 60 : 900);
        resp.setPassScore(unit.getPassScore());
        resp.setAudience(splitAudienceAll(unit.getAudience()));

        // 场景描述六项 + 行为目标/冰山要素（无值时给空数组，不造占位内容）
        resp.setBusinessGoal(unit.getBusinessGoal());
        resp.setBusinessImpact(unit.getBusinessImpact());
        resp.setFailureCost(unit.getFailureCost());
        resp.setCoreConflict(unit.getCoreConflict());
        resp.setBehaviorGoals(nullSafeList(unit.getBehaviorGoals()));
        resp.setIcebergElements(nullSafeList(unit.getIcebergElements()));
        resp.setCommonFailureModes(nullSafeList(unit.getCommonFailureModes()));
        resp.setDifficulties(nullSafeList(unit.getDifficulties()));
        resp.setForbiddenBehaviors(nullSafeList(unit.getForbiddenBehaviors()));

        // 我的统计（训练/考核分开计，与主题详情场景卡同口径）
        long trainingCount = quotaService.countFormalAttempts(
                userId, themeId, unit.getId(), MODE_TRAINING);
        long examCount = quotaService.countFormalAttempts(
                userId, themeId, unit.getId(), MODE_EXAM);
        resp.setMyTrainingCount(trainingCount);
        resp.setMyExamCount(examCount);
        resp.setAttemptCount(trainingCount + examCount);
        List<ActPracticeReport> trainingReports = reportsOf(userId, themeId, unit.getId(), MODE_TRAINING);
        List<ActPracticeReport> examReports = reportsOf(userId, themeId, unit.getId(), MODE_EXAM);
        resp.setMyHighestScore(highestOf(trainingReports));
        resp.setMyHighestExamScore(highestOf(examReports));
        boolean passed = anyPassed(trainingReports) || anyPassed(examReports);
        String status = passed ? "passed" : (resp.getAttemptCount() > 0 ? "in_progress" : "not_started");
        resp.setStatus(status);
        resp.setStatusText(switch (status) {
            case "passed" -> "已通过";
            case "in_progress" -> "进行中";
            default -> "未开始";
        });

        resp.setDifficulty(settings.getDifficulty());
        resp.setDifficultyText(difficultyText(settings.getDifficulty()));

        // 准入状态与两个动作（与主题详情同一套判定，前端不重复推导）
        String stopReason = resolveStopReason(theme, settings);
        resp.setStartable(stopReason == null);
        resp.setStopReason(stopReason);
        resp.setTrainingAction(toScenarioAction(trainingAction(settings, stopReason == null, stopReason)));
        resp.setExamAction(toScenarioAction(examAction(settings, stopReason == null, stopReason, userId, themeId)));

        // 评估维度与权重（弹窗"场景评估维度和权重"分区）
        resp.setDimensions(dimensionMapper.selectList(
                        new LambdaQueryWrapper<ActEvaluationDimension>()
                                .eq(ActEvaluationDimension::getScenarioUnitId, unit.getId())
                                .orderByAsc(ActEvaluationDimension::getSortOrder))
                .stream().map(dimension -> {
                    StudentScenarioDetailResp.Dimension item = new StudentScenarioDetailResp.Dimension();
                    item.setId(dimension.getId());
                    item.setName(dimension.getDimensionName());
                    item.setWeight(dimension.getWeight());
                    item.setDescription(dimension.getDimensionDesc());
                    item.setExcellentStandard(dimension.getExcellentStandard());
                    item.setGoodStandard(dimension.getGoodStandard());
                    item.setImproveStandard(dimension.getImproveStandard());
                    item.setSortOrder(dimension.getSortOrder());
                    return item;
                }).toList());

        // 实战案例：发布快照为准（act_drill_script 每案例一行），老数据回退 act_case_script
        resp.setCases(loadCaseSummaries(themeId, version, unit.getId()));
        // 涉及产品档案：发布快照行 + 场景关联（无关联就是空数组）
        resp.setProductProfiles(loadScenarioProductProfiles(themeId, version, unit.getId()));

        // 可用练习方式：设置 CSV 与场景能力取交集；全不可用时说明原因（契约 F09 末尾）
        List<String> enabledMethods = splitCsv(settings.getPracticeMethods());
        resp.setPracticeMethods(enabledMethods);
        if (enabledMethods.isEmpty()) {
            resp.setUnavailableReason(resp.getStopReason() != null
                    ? resp.getStopReason() : "该主题未开启任何练习方式");
        } else if (!resp.isStartable()) {
            resp.setUnavailableReason(resp.getStopReason());
        }
        return resp;
    }

    /**
     * 随机练习取档（契约 08 §4 随机分支，2026-11-04 新增）。
     * <p>
     * 鉴权与场景准备详情完全同一套（参与关系 → 已发布 → 场景属当前版本），
     * 因为两者都是"学员端在发布快照下查看某个场景"的读操作。
     */
    @Override
    public RandomScriptResp randomScript(String userId, String themeId, String scenarioId,
                                         int difficulty) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        ActThemeParticipant participation = participantMapper.selectOne(
                new LambdaQueryWrapper<ActThemeParticipant>()
                        .eq(ActThemeParticipant::getThemeId, themeId)
                        .eq(ActThemeParticipant::getUserId, userId)
                        .eq(ActThemeParticipant::getStatus, PARTICIPANT_ACTIVE)
                        .last("LIMIT 1"));
        if (participation == null) {
            throw new BizException("你不在该主题的学员名单中");
        }
        Integer version = theme.getPublishedVersionNo();
        if (version == null) {
            throw new BizException("主题尚未发布");
        }
        ActScenarioUnit unit = unitMapper.selectOne(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, themeId)
                .eq(ActScenarioUnit::getVersionNo, version)
                .eq(ActScenarioUnit::getId, scenarioId)
                .last("LIMIT 1"));
        if (unit == null) {
            throw new BizException("场景不存在或已下线");
        }
        // 难度越界一律归一到 3 档内（前端传错不 500；越界值按最近档）
        int normalised = difficulty <= 1 ? 1 : (difficulty >= 3 ? 3 : difficulty);
        RandomScriptService.PickResult picked =
                randomScriptService.pick(themeId, version, scenarioId, normalised);
        RandomScriptResp resp = new RandomScriptResp();
        resp.setRequestedDifficulty(picked.requestedDifficulty());
        resp.setDifficulty(picked.actualDifficulty());
        resp.setDegraded(picked.degraded());
        resp.setScript(picked.script());
        return resp;
    }

    /**
     * 实战案例摘要（F09 弹窗"实战案例 N 个"分区）。
     * <p>
     * 数据源优先级：① 当前发布版本的 act_drill_script（发布快照，含生成时的案例正文原话）；
     * ② 该版本 act_case_script 落库列（老数据/发布尚未编译时只有列）。
     * 两者都按案例 ID 对齐，快照缺字段时逐字段回退到案例行，避免弹窗出现空白字段。
     * <p>
     * 顺序按案例行 sort 的等价口径：脚本表没有 sort_order，用 act_case_script 的创建顺序
     * 对齐（先查案例行拿顺序，再按顺序填快照），保证同一场景的案例顺序稳定。
     */
    private List<StudentScenarioDetailResp.CaseSummary> loadCaseSummaries(
            String themeId, Integer version, String scenarioUnitId) {
        List<ActCaseScript> caseRows = caseScriptMapper.selectList(
                new LambdaQueryWrapper<ActCaseScript>()
                        .eq(ActCaseScript::getThemeId, themeId)
                        .eq(ActCaseScript::getVersionNo, version)
                        .eq(ActCaseScript::getScenarioUnitId, scenarioUnitId)
                        .orderByAsc(ActCaseScript::getCreatedAt));
        if (caseRows.isEmpty()) {
            return List.of();
        }
        // 一次批量取快照，避免逐个案例查一次（案例数通常 1~4，批量仍然更稳）
        Map<String, ActDrillScript> drillByCaseId = drillScriptMapper.selectList(
                        new LambdaQueryWrapper<ActDrillScript>()
                                .eq(ActDrillScript::getThemeId, themeId)
                                .eq(ActDrillScript::getVersionNo, version)
                                .in(ActDrillScript::getCaseScriptId,
                                        caseRows.stream().map(ActCaseScript::getId).toList())
                                .orderByDesc(ActDrillScript::getCompiledAt))
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        ActDrillScript::getCaseScriptId, drill -> drill, (first, second) -> first));
        List<StudentScenarioDetailResp.CaseSummary> items = new ArrayList<>();
        for (ActCaseScript row : caseRows) {
            ActDrillScript drill = drillByCaseId.get(row.getId());
            ObjectNode payload = payloadOf(drill);
            StudentScenarioDetailResp.CaseSummary item = new StudentScenarioDetailResp.CaseSummary();
            item.setCaseId(row.getId());
            item.setDrillScriptId(drill == null ? null : drill.getId());
            item.setScriptName(firstText(payload, "scriptName", row.getScriptName()));
            Integer difficulty = payloadDifficulty(payload, row.getDifficulty());
            item.setDifficulty(difficulty);
            item.setDifficultyStars(difficulty);
            item.setActorName(firstText(payload, "actorName", row.getActorName()));
            item.setActorPosition(firstText(payload, "actorPosition", row.getActorPosition()));
            item.setActorCompany(firstText(payload, "actorCompany", row.getActorCompany()));
            item.setActorType(firstText(payload, "actorType", row.getActorType()));
            item.setActorPersonality(firstText(payload, "actorPersonality", row.getActorPersonality()));
            item.setActorCommunicationStyle(
                    firstText(payload, "actorCommunicationStyle", row.getActorCommunicationStyle()));
            item.setScriptBackground(firstText(payload, "scriptBackground", row.getScriptBackground()));
            // 开场白原话：原平台弹窗逐字展示，不允许截断或重写，所以不做任何加工
            item.setScriptOpening(firstText(payload, "scriptOpening", row.getScriptOpening()));
            items.add(item);
        }
        return items;
    }

    /** 快照 payload → ObjectNode；脏数据（非对象 JSON）当作"无快照"，回退案例行 */
    private ObjectNode payloadOf(ActDrillScript drill) {
        if (drill == null || drill.getCompiledPayload() == null || drill.getCompiledPayload().isBlank()) {
            return null;
        }
        try {
            return JsonSupport.obj(drill.getCompiledPayload());
        } catch (BizException e) {
            log.warn("案例快照 JSON 非法，回退案例行 drillId={}", drill.getId());
            return null;
        }
    }

    private Integer payloadDifficulty(ObjectNode payload, Integer fallback) {
        Integer value = JsonSupport.integer(payload, "difficulty");
        return value == null ? fallback : value;
    }

    /** 快照字段优先，缺失回退案例行字段；两者都没有则保留 null（前端据此省略该行） */
    private String firstText(ObjectNode payload, String field, String fallback) {
        String value = JsonSupport.text(payload, field);
        if (value == null || value.isBlank()) {
            return fallback == null || fallback.isBlank() ? null : fallback;
        }
        return value;
    }

    /**
     * 场景关联的产品档案（F09"涉及产品档案"分区）。
     * <p>
     * 关联关系在 act_scenario_product_profile（按 scenario_unit_id）；档案本体取发布快照行
     * （content_version_no = 当前发布版本）。因为历史发布流程可能没有把 v0 档案复制进版本，
     * 这里的口径是：<b>优先版本行</b>，同一 profile_key 在版本里找不到时回退到工作区行（v0），
     * 这样老数据不会因为缺快照而让弹窗整块空白；但绝不凭空造档案。
     * <p>
     * <b>兜底分支的生命周期</b>：这是为兼容「发布逻辑修复（ContentVersionServiceImpl 开始复制
     * 产品档案）之前」产生的历史数据而保留的防御性设计，成本为零。等产品档案数据回填到发布版本后
     * 可移除此分支（见 {@code copyProductProfiles} 与该分支上的同款注释）。
     */
    private List<StudentScenarioDetailResp.ProductProfileBrief> loadScenarioProductProfiles(
            String themeId, Integer version, String scenarioUnitId) {
        List<String> linkedIds = scenarioProductProfileMapper.selectList(
                        new LambdaQueryWrapper<ActScenarioProductProfile>()
                                .eq(ActScenarioProductProfile::getScenarioUnitId, scenarioUnitId)
                                .orderByAsc(ActScenarioProductProfile::getSortOrder))
                .stream()
                .map(ActScenarioProductProfile::getProductProfileId)
                .toList();
        if (linkedIds.isEmpty()) {
            return List.of();
        }
        List<ActProductProfile> published = productProfileMapper.selectList(
                new LambdaQueryWrapper<ActProductProfile>()
                        .eq(ActProductProfile::getThemeId, themeId)
                        .eq(ActProductProfile::getContentVersionNo, version)
                        .in(ActProductProfile::getId, linkedIds)
                        .orderByAsc(ActProductProfile::getSortOrder));
        // 兜底：发布逻辑修复前的历史数据，v1 版本行是空的（当时发布不复制产品档案），
        // 这里回退到工作区 v0 行，让老主题仍能看到档案。等产品档案数据回填到发布版本后可移除此分支。
        List<ActProductProfile> resolved = published.isEmpty()
                ? productProfileMapper.selectBatchIds(linkedIds) : published;
        // 关联顺序以 act_scenario_product_profile.sort_order 为准，档案表自己的 sort_order 只作兜底
        Map<String, Integer> linkOrder = new LinkedHashMap<>();
        for (int i = 0; i < linkedIds.size(); i++) {
            linkOrder.put(linkedIds.get(i), i);
        }
        return resolved.stream()
                .sorted(java.util.Comparator.comparingInt(
                        profile -> linkOrder.getOrDefault(profile.getId(), Integer.MAX_VALUE)))
                .map(profile -> {
                    StudentScenarioDetailResp.ProductProfileBrief brief =
                            new StudentScenarioDetailResp.ProductProfileBrief();
                    brief.setProfileId(profile.getId());
                    brief.setProfileKey(profile.getProfileKey());
                    brief.setName(profile.getProfileName());
                    brief.setCategory(profile.getCategory());
                    brief.setDescription(profile.getDescription());
                    return brief;
                }).toList();
    }

    /** 适用人群全量切分（弹窗展示全部标签，不截断到前两个） */
    private List<String> splitAudienceAll(String audience) {
        if (audience == null || audience.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(audience.split("[/、,，|]"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** Nullable 列表 → 空列表（老数据 JSON 列为 null 时前端拿到 []，不用判空） */
    private List<String> nullSafeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    /** 训练动作可用性（主题详情与场景准备弹窗共用同一判定，避免两处口径漂移） */
    private StudentThemeDetailResp.Action trainingAction(ActThemeSettings settings,
                                                         boolean startable, String stopReason) {
        boolean trainingOn = Objects.equals(settings.getTrainingModeEnabled(), 1);
        return StudentThemeDetailResp.Action.of(
                trainingOn && startable,
                !trainingOn ? "该主题未开启训练模式" : stopReason);
    }

    /** 考核动作可用性（含混合模式解锁门槛，同 themeDetail 原口径） */
    private StudentThemeDetailResp.Action examAction(ActThemeSettings settings,
                                                      boolean startable, String stopReason,
                                                      String userId, String themeId) {
        if (!Objects.equals(settings.getAssessmentModeEnabled(), 1)) {
            return StudentThemeDetailResp.Action.of(false, "该主题未开启考核模式");
        }
        if (!startable) {
            return StudentThemeDetailResp.Action.of(false, stopReason);
        }
        Integer minTrain = settings.getMinTrainCountToUnlockExam();
        boolean unlocked = quotaService.examUnlocked(userId, themeId, minTrain == null ? 0 : minTrain);
        return StudentThemeDetailResp.Action.of(unlocked,
                "完成 " + (minTrain == null ? 0 : minTrain) + " 次训练后解锁考核");
    }

    /**
     * 主题详情 Action → 场景准备 Action。
     * <p>
     * 两个 DTO 的 Action 结构完全相同（enabled + reason），但 Java 里是不同的嵌套类型；
     * 这里做一次显式转换而不是抽公共类，是因为响应 DTO 由前端契约决定、不宜为了复用改结构。
     */
    private StudentScenarioDetailResp.Action toScenarioAction(StudentThemeDetailResp.Action source) {
        if (source == null) {
            return null;
        }
        return StudentScenarioDetailResp.Action.of(source.isEnabled(), source.getReason());
    }

    /** 计划期/主题状态的准入原因；null 表示可以开始新 attempt（与 preflight 同口径） */
    private String resolveStopReason(ActTheme theme, ActThemeSettings settings) {
        if (!Objects.equals(theme.getStatus(), THEME_PUBLISHED)) {
            return "主题当前不可练习（已暂停或已结束）";
        }
        LocalDateTime now = LocalDateTime.now();
        if (settings.getPlanStartTime() != null && now.isBefore(settings.getPlanStartTime())) {
            return "主题尚未到开始时间";
        }
        if (settings.getPlanEndTime() != null && now.isAfter(settings.getPlanEndTime())) {
            return "主题已过计划结束时间，无法开始新练习";
        }
        return null;
    }

    private String difficultyText(Integer difficulty) {
        if (difficulty == null) {
            return null;
        }
        return switch (difficulty) {
            case 1 -> "简单";
            case 3 -> "困难";
            default -> "中等";
        };
    }

    /** CSV（voice,text / case,custom,random）→ 列表（去空白、去空项） */
    private List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /**
     * 适用人群 → 标签列表。
     * <p>
     * act_scenario_unit.audience 是单个字符串（"0-6个月销售 / 新入职销售"），
     * 契约要求前端展示"前两个标签"，所以在服务端按 / 和 、 切分，避免各页面各切一遍。
     */
    private List<String> splitAudience(String audience) {
        if (audience == null || audience.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(audience.split("[/、,，|]"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .limit(AUDIENCE_TAG_LIMIT)
                .toList();
    }

    /** 材料类型文案（契约 F08：卡片展示名称、类型、大小、来源） */
    private String materialTypeText(ActContentVersionMaterial material) {
        if ("online_classroom".equals(material.getSourceType())) {
            return "在线课堂";
        }
        String fileType = material.getFileType();
        return fileType == null || fileType.isBlank() ? "文档" : fileType.toUpperCase();
    }

    /** 发布快照里的产品档案（含自定义字段解析） */
    private List<StudentThemeDetailResp.ProductProfile> loadProductProfiles(String themeId,
                                                                           Integer version) {
        List<ActProductProfile> profiles = productProfileMapper.selectList(
                new LambdaQueryWrapper<ActProductProfile>()
                        .eq(ActProductProfile::getThemeId, themeId)
                        .eq(ActProductProfile::getContentVersionNo, version)
                        .orderByAsc(ActProductProfile::getSortOrder));
        List<StudentThemeDetailResp.ProductProfile> items = new ArrayList<>();
        for (ActProductProfile profile : profiles) {
            StudentThemeDetailResp.ProductProfile item = new StudentThemeDetailResp.ProductProfile();
            item.setProfileKey(profile.getProfileKey());
            item.setName(profile.getProfileName());
            item.setCategory(profile.getCategory());
            item.setDescription(profile.getDescription());
            item.setDetailMarkdown(profile.getDetailMarkdown());
            item.setCustomFields(parseCustomFields(profile.getCustomFields()));
            items.add(item);
        }
        return items;
    }

    /** custom_fields JSON 数组 → DTO 列表；脏数据只跳过该行，不让整个详情页 500 */
    private List<StudentThemeDetailResp.CustomField> parseCustomFields(String json) {
        List<StudentThemeDetailResp.CustomField> fields = new ArrayList<>();
        var array = JsonSupport.arr(json);
        if (array == null) {
            return fields;
        }
        for (var node : array) {
            if (!node.isObject()) {
                continue;
            }
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            StudentThemeDetailResp.CustomField field = new StudentThemeDetailResp.CustomField();
            field.setKey(JsonSupport.text(object, "key"));
            field.setValue(JsonSupport.text(object, "value"));
            field.setSortOrder(JsonSupport.integer(object, "sortOrder"));
            fields.add(field);
        }
        return fields;
    }

    /** 我的最近训练记录（按开始时间倒序，报告未生成时分数为 null） */
    private List<StudentThemeDetailResp.Record> loadRecentRecords(String userId, String themeId) {
        Page<ActPracticeAttempt> page = attemptMapper.selectPage(
                Page.of(1, DETAIL_RECORD_LIMIT),
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getUserId, userId)
                        .eq(ActPracticeAttempt::getThemeId, themeId)
                        .orderByDesc(ActPracticeAttempt::getCreatedAt));
        List<ActPracticeAttempt> rows = page.getRecords();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, ActPracticeReport> reportByAttempt = reportByAttempts(
                rows.stream().map(ActPracticeAttempt::getId).toList());
        Map<String, String> scenarioNames = scenarioNamesOf(
                rows.stream().map(ActPracticeAttempt::getScenarioId).distinct().toList());
        return rows.stream().map(attempt -> {
            StudentThemeDetailResp.Record record = new StudentThemeDetailResp.Record();
            record.setAttemptId(attempt.getId());
            record.setScenarioId(attempt.getScenarioId());
            record.setScenarioName(scenarioNames.get(attempt.getScenarioId()));
            record.setStudyMode(attempt.getStudyMode());
            record.setStatus(attempt.getStatus());
            record.setStartedAt(attempt.getStartedAt());
            record.setEndedAt(attempt.getEndedAt());
            ActPracticeReport report = reportByAttempt.get(attempt.getId());
            if (report != null) {
                record.setTotalScore(report.getTotalScore());
                record.setPassed(report.getPassed());
            }
            return record;
        }).toList();
    }

    @Override
    public PageResult<StudentHistoryResp> history(String userId, String themeId,
                                                  Integer studyMode, int page, int size) {
        LambdaQueryWrapper<ActPracticeAttempt> wrapper = new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getUserId, userId)
                .orderByDesc(ActPracticeAttempt::getCreatedAt);
        if (themeId != null && !themeId.isBlank()) {
            wrapper.eq(ActPracticeAttempt::getThemeId, themeId);
        }
        if (studyMode != null) {
            wrapper.eq(ActPracticeAttempt::getStudyMode, studyMode);
        }
        Page<ActPracticeAttempt> result = attemptMapper.selectPage(Page.of(page, size), wrapper);
        List<ActPracticeAttempt> rows = result.getRecords();

        // 报告批量查 + 主题/场景名批量补齐（无 JOIN）
        Map<String, ActPracticeReport> reportByAttempt = reportByAttempts(
                rows.stream().map(ActPracticeAttempt::getId).toList());
        Map<String, String> themeNames = themeNamesOf(
                rows.stream().map(ActPracticeAttempt::getThemeId).distinct().toList());
        Map<String, String> scenarioNames = scenarioNamesOf(
                rows.stream().map(ActPracticeAttempt::getScenarioId).distinct().toList());

        List<StudentHistoryResp> items = rows.stream().map(attempt -> {
            StudentHistoryResp item = new StudentHistoryResp();
            item.setAttemptId(attempt.getId());
            item.setThemeId(attempt.getThemeId());
            item.setThemeName(themeNames.get(attempt.getThemeId()));
            item.setScenarioId(attempt.getScenarioId());
            item.setScenarioName(scenarioNames.get(attempt.getScenarioId()));
            item.setStudyMode(attempt.getStudyMode());
            item.setPracticeType(attempt.getPracticeType());
            item.setStatus(attempt.getStatus());
            item.setStartedAt(attempt.getStartedAt());
            item.setEndedAt(attempt.getEndedAt());
            item.setEndReason(attempt.getEndReason());
            item.setTimedOut(attempt.getTimedOut());
            ActPracticeReport report = reportByAttempt.get(attempt.getId());
            if (report != null) {
                item.setTotalScore(report.getTotalScore());
                item.setPassScore(report.getPassScore());
                item.setPassed(report.getPassed());
                item.setTargetReached(report.getTargetReached());
            }
            return item;
        }).toList();
        return new PageResult<>(items, result.getTotal());
    }

    /**
     * 练习历程页（契约 04 §API-S07 / 01 §F20）。
     * <p>
     * 统计口径固定为「该学员全部 attempt」，与当前筛选无关——否则 Tab 上的数字会随筛选自己跳。
     * 记录列表走数据库分页；status=passed/failed 需要报告判定，按报告反查 attemptId 再过滤
     * （不跨表 JOIN 铁律），无匹配报告时直接返回空页。
     */
    @Override
    public StudentPracticeRecordsResp practiceRecords(String userId, String status, String themeId,
                                                      Integer studyMode, int page, int size) {
        List<ActPracticeAttempt> all = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getUserId, userId)
                        .orderByDesc(ActPracticeAttempt::getCreatedAt));
        Map<String, ActPracticeReport> allReports = reportByAttempts(
                all.stream().map(ActPracticeAttempt::getId).toList());

        StudentPracticeRecordsResp resp = new StudentPracticeRecordsResp();
        resp.setStats(buildPracticeStats(all, allReports));
        resp.setThemes(themeOptionsOf(all));

        LambdaQueryWrapper<ActPracticeAttempt> wrapper = new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getUserId, userId)
                .orderByDesc(ActPracticeAttempt::getCreatedAt);
        if (themeId != null && !themeId.isBlank()) {
            wrapper.eq(ActPracticeAttempt::getThemeId, themeId);
        }
        if (studyMode != null) {
            wrapper.eq(ActPracticeAttempt::getStudyMode, studyMode);
        }
        String bucket = status == null || status.isBlank() ? "all" : status;
        if ("in_progress".equals(bucket)) {
            wrapper.in(ActPracticeAttempt::getStatus, IN_PROGRESS_STATUSES);
        } else if ("passed".equals(bucket) || "failed".equals(bucket)) {
            List<String> attemptIds = allReports.values().stream()
                    .filter(report -> "passed".equals(bucket)
                            ? report.getPassed() != null && report.getPassed() == 1
                            : report.getPassed() != null && report.getPassed() == 0)
                    .map(ActPracticeReport::getAttemptId)
                    .toList();
            if (attemptIds.isEmpty()) {
                resp.setRecords(List.of());
                resp.setTotal(0);
                return resp;
            }
            wrapper.in(ActPracticeAttempt::getId, attemptIds);
        }

        Page<ActPracticeAttempt> result = attemptMapper.selectPage(Page.of(page, size), wrapper);
        resp.setRecords(toHistoryItems(result.getRecords()));
        resp.setTotal(result.getTotal());
        return resp;
    }

    /** 四项统计 + 三 Tab 计数（口径见 {@link #practiceRecords}） */
    private StudentPracticeRecordsResp.Stats buildPracticeStats(
            List<ActPracticeAttempt> attempts, Map<String, ActPracticeReport> reports) {
        StudentPracticeRecordsResp.Stats stats = new StudentPracticeRecordsResp.Stats();
        long totalSeconds = 0;
        BigDecimal scoreSum = BigDecimal.ZERO;
        int scored = 0;
        long passed = 0;
        long failed = 0;
        long running = 0;
        java.util.Set<String> themes = new java.util.LinkedHashSet<>();
        for (ActPracticeAttempt attempt : attempts) {
            themes.add(attempt.getThemeId());
            if (attempt.getStartedAt() != null && attempt.getEndedAt() != null) {
                totalSeconds += java.time.Duration.between(
                        attempt.getStartedAt(), attempt.getEndedAt()).getSeconds();
            }
            ActPracticeReport report = reports.get(attempt.getId());
            if (report != null && report.getTotalScore() != null) {
                scoreSum = scoreSum.add(report.getTotalScore());
                scored++;
            }
            if (IN_PROGRESS_STATUSES.contains(attempt.getStatus())) {
                running++;
            } else if (report != null && report.getPassed() != null && report.getPassed() == 1) {
                passed++;
            } else {
                failed++;
            }
        }
        stats.setTotalCount(attempts.size());
        stats.setTotalHours(BigDecimal.valueOf(totalSeconds)
                .divide(BigDecimal.valueOf(3600), 2, java.math.RoundingMode.HALF_UP));
        stats.setAverageScore(scored == 0 ? null
                : scoreSum.divide(BigDecimal.valueOf(scored), 1, java.math.RoundingMode.HALF_UP));
        stats.setThemeCount(themes.size());
        stats.setPassedCount(passed);
        stats.setFailedCount(failed);
        stats.setInProgressCount(running);
        return stats;
    }

    /** 所属主题下拉：只列有记录的主题（契约 F20） */
    private List<StudentPracticeRecordsResp.ThemeOption> themeOptionsOf(
            List<ActPracticeAttempt> attempts) {
        List<String> themeIds = attempts.stream()
                .map(ActPracticeAttempt::getThemeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<String, String> names = themeNamesOf(themeIds);
        return themeIds.stream().map(themeId -> {
            StudentPracticeRecordsResp.ThemeOption option = new StudentPracticeRecordsResp.ThemeOption();
            option.setThemeId(themeId);
            option.setThemeName(names.get(themeId));
            return option;
        }).toList();
    }

    /** attempt + 报告摘要 → 历程条目（报告缺失时分数等字段为 null） */
    private List<StudentHistoryResp> toHistoryItems(List<ActPracticeAttempt> rows) {
        Map<String, ActPracticeReport> reportByAttempt = reportByAttempts(
                rows.stream().map(ActPracticeAttempt::getId).toList());
        Map<String, String> themeNames = themeNamesOf(
                rows.stream().map(ActPracticeAttempt::getThemeId).distinct().toList());
        Map<String, String> scenarioNames = scenarioNamesOf(
                rows.stream().map(ActPracticeAttempt::getScenarioId).distinct().toList());
        return rows.stream().map(attempt -> {
            StudentHistoryResp item = new StudentHistoryResp();
            item.setAttemptId(attempt.getId());
            item.setThemeId(attempt.getThemeId());
            item.setThemeName(themeNames.get(attempt.getThemeId()));
            item.setScenarioId(attempt.getScenarioId());
            item.setScenarioName(scenarioNames.get(attempt.getScenarioId()));
            item.setStudyMode(attempt.getStudyMode());
            item.setPracticeType(attempt.getPracticeType());
            item.setStatus(attempt.getStatus());
            item.setStartedAt(attempt.getStartedAt());
            item.setEndedAt(attempt.getEndedAt());
            item.setEndReason(attempt.getEndReason());
            item.setTimedOut(attempt.getTimedOut());
            ActPracticeReport report = reportByAttempt.get(attempt.getId());
            if (report != null) {
                item.setTotalScore(report.getTotalScore());
                item.setPassScore(report.getPassScore());
                item.setPassed(report.getPassed());
                item.setTargetReached(report.getTargetReached());
                item.setDurationSeconds(report.getDurationSeconds());
            }
            return item;
        }).toList();
    }

    /**
     * 主题卡状态（契约 F04），服务端判定优先级：全部通过 &gt; 计划结束 &gt; 有记录 &gt; 未开始。
     * 放在服务端是为了让首页卡片与练习历程列表用同一套口径，前端不重复推导。
     */
    private String resolveCardStatus(LocalDateTime planEndTime, long passed,
                                     long scenarioCount, long trainingCount) {
        if (scenarioCount > 0 && passed >= scenarioCount) {
            return "passed";
        }
        if (planEndTime != null && planEndTime.isBefore(LocalDateTime.now())) {
            return "ended";
        }
        if (trainingCount > 0 || passed > 0) {
            return "in_progress";
        }
        return "not_started";
    }

    /** 已发布版本的场景总数（首页"3 个场景" + 进度分母） */
    private long countPublishedScenarios(ActTheme theme) {
        if (theme.getPublishedVersionNo() == null) {
            return 0;
        }
        Long count = unitMapper.selectCount(new LambdaQueryWrapper<ActScenarioUnit>()
                .eq(ActScenarioUnit::getThemeId, theme.getId())
                .eq(ActScenarioUnit::getVersionNo, theme.getPublishedVersionNo()));
        return count == null ? 0 : count;
    }

    /** 单主题通过场景数：训练报告 passed=1 的场景去重 */
    private long countPassedScenarios(String userId, String themeId, int studyMode) {
        return reportMapper.selectList(new LambdaQueryWrapper<ActPracticeReport>()
                        .eq(ActPracticeReport::getUserId, userId)
                        .eq(ActPracticeReport::getThemeId, themeId)
                        .eq(ActPracticeReport::getStudyMode, studyMode)
                        .eq(ActPracticeReport::getPassed, 1))
                .stream()
                .map(ActPracticeReport::getScenarioId)
                .distinct()
                .count();
    }

    private void fillPassedAndHighest(String userId, String themeId, String scenarioId,
                                      StudentThemeDetailResp.Scenario scenario) {
        List<ActPracticeReport> training = reportsOf(userId, themeId, scenarioId, MODE_TRAINING);
        scenario.setMyTrainingPassed(anyPassed(training));
        scenario.setMyHighestScore(highestOf(training));

        List<ActPracticeReport> exam = reportsOf(userId, themeId, scenarioId, MODE_EXAM);
        scenario.setMyExamPassed(anyPassed(exam));
        scenario.setMyHighestExamScore(highestOf(exam));
    }

    private List<ActPracticeReport> reportsOf(String userId, String themeId, String scenarioId,
                                             int studyMode) {
        return reportMapper.selectList(new LambdaQueryWrapper<ActPracticeReport>()
                .eq(ActPracticeReport::getUserId, userId)
                .eq(ActPracticeReport::getThemeId, themeId)
                .eq(ActPracticeReport::getScenarioId, scenarioId)
                .eq(ActPracticeReport::getStudyMode, studyMode)
                .orderByDesc(ActPracticeReport::getGeneratedAt));
    }

    private boolean anyPassed(List<ActPracticeReport> reports) {
        return reports.stream().anyMatch(report -> report.getPassed() != null && report.getPassed() == 1);
    }

    private BigDecimal highestOf(List<ActPracticeReport> reports) {
        return reports.stream()
                .map(ActPracticeReport::getTotalScore)
                .filter(score -> score != null)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private long examRemaining(String userId, ActTheme theme, ActThemeSettings settings,
                               ActThemeParticipant participation) {
        int max = settings.getMaxExamCount() == null ? 0 : settings.getMaxExamCount();
        if (max <= 0) {
            // 0 = 不限，前端展示为"不限"
            return -1;
        }
        int extra = participation.getExtraExamAttemptCount() == null
                ? 0 : participation.getExtraExamAttemptCount();
        long used = quotaService.countDeductedAttempts(userId, theme.getId(), MODE_EXAM);
        return Math.max(0, (long) max + extra - used);
    }

    @Override
    public StudentHomeSummaryResp homeSummary(String userId) {
        // 一次性聚合原平台首页三栏所需数据（契约 04 §3 API-S01）：
        // user（姓名/部门/岗位）+ stats（学时/次数/主题数/荣誉分）+ themes + opportunities + recentPractice
        List<StudentHomeResp.Card> themeCards = home(userId).getThemes();

        StudentHomeSummaryResp resp = new StudentHomeSummaryResp();
        StudentHomeSummaryResp.User user = new StudentHomeSummaryResp.User();
        // 本工程不直读 sys_*（AGENTS 铁律 1）：姓名/部门/岗位由前端用已有登录信息补齐，
        // 这里只回 userId，避免为展示信息引入 Dubbo OrgQueryService（偏差已在提交说明记录）。
        user.setUserId(userId);
        resp.setUser(user);
        resp.setThemes(themeCards);
        resp.setOpportunities(opportunities(userId, OPPORTUNITY_LIMIT));

        // 四项统计：学时/次数按已结束 attempt 汇总，主题数按参与关系，荣誉分按报告总分累加
        List<ActPracticeAttempt> attempts = attemptMapper.selectList(
                new LambdaQueryWrapper<ActPracticeAttempt>()
                        .eq(ActPracticeAttempt::getUserId, userId)
                        .isNotNull(ActPracticeAttempt::getStartedAt));
        StudentHomeSummaryResp.Stats stats = new StudentHomeSummaryResp.Stats();
        long totalSeconds = 0;
        long deducted = 0;
        for (ActPracticeAttempt attempt : attempts) {
            if (attempt.getEndedAt() != null && attempt.getStartedAt() != null) {
                totalSeconds += java.time.Duration.between(
                        attempt.getStartedAt(), attempt.getEndedAt()).getSeconds();
            }
            if (attempt.getQuotaDeducted() != null && attempt.getQuotaDeducted() == 1) {
                deducted++;
            }
        }
        stats.setTotalHours(BigDecimal.valueOf(totalSeconds)
                .divide(BigDecimal.valueOf(3600), 2, java.math.RoundingMode.HALF_UP));
        stats.setPracticeCount(deducted);
        stats.setThemeCount(themeCards.size());
        stats.setHonorScore(sumHonorScore(userId));
        resp.setStats(stats);

        // 最近 1 条（D09：首页只 1 条）
        PageResult<StudentHistoryResp> recent = history(userId, null, null, 1, 1);
        resp.setRecentPractice(recent.getRecords().isEmpty() ? null : recent.getRecords().get(0));
        return resp;
    }

    /** 荣誉分：已完成报告总分向下取整累加（契约"首期可为固定展示值"，此处用真实数据更直观） */
    private int sumHonorScore(String userId) {
        List<ActPracticeReport> reports = reportMapper.selectList(
                new LambdaQueryWrapper<ActPracticeReport>()
                        .eq(ActPracticeReport::getUserId, userId)
                        .isNotNull(ActPracticeReport::getTotalScore));
        BigDecimal total = reports.stream()
                .map(ActPracticeReport::getTotalScore)
                .filter(score -> score != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return total.intValue();
    }

    private Map<String, ActPracticeReport> reportByAttempts(List<String> attemptIds) {
        if (attemptIds.isEmpty()) {
            return Map.of();
        }
        return reportMapper.selectList(new LambdaQueryWrapper<ActPracticeReport>()
                        .in(ActPracticeReport::getAttemptId, attemptIds))
                .stream()
                .collect(LinkedHashMap::new,
                        (map, report) -> map.put(report.getAttemptId(), report),
                        Map::putAll);
    }

    private Map<String, String> themeNamesOf(List<String> themeIds) {
        if (themeIds.isEmpty()) {
            return Map.of();
        }
        return themeMapper.selectBatchIds(themeIds).stream()
                .collect(LinkedHashMap::new,
                        (map, theme) -> map.put(theme.getId(), theme.getThemeName()),
                        Map::putAll);
    }

    private Map<String, String> scenarioNamesOf(List<String> scenarioIds) {
        if (scenarioIds.isEmpty()) {
            return Map.of();
        }
        return unitMapper.selectBatchIds(scenarioIds).stream()
                .collect(LinkedHashMap::new,
                        (map, unit) -> map.put(unit.getId(), unit.getUnitName()),
                        Map::putAll);
    }
}





