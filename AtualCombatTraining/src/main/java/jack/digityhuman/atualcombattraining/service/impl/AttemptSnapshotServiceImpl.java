package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActEvaluationDimension;
import jack.digityhuman.atualcombattraining.entity.ActProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioProductProfile;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActThemeSettings;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActEvaluationDimensionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioProductProfileMapper;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.service.AttemptSnapshotService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * attempt 快照实现：把设置/场景/档案/材料固化成 JSON 字符串。
 * <p>
 * 为什么自己组装 ObjectNode 而不是直接序列化实体：实体的 LocalDateTime 需要全局 Jackson 配置，
 * 而快照是"跨时间可读的证据"，用固定格式（yyyy-MM-dd HH:mm:ss）自己写更稳；
 * 同时能显式控制哪些字段进快照（例如不把 owner_user_id 之类内部字段带给运行态）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttemptSnapshotServiceImpl implements AttemptSnapshotService {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** 学员可见的材料 */
    private static final String VISIBILITY_STUDENT = "student";

    private final ActScenarioUnitMapper unitMapper;
    private final ActEvaluationDimensionMapper dimensionMapper;
    private final ActProductProfileMapper profileMapper;
    private final ActScenarioProductProfileMapper scenarioProfileMapper;
    private final ActContentVersionMaterialMapper materialMapper;

    @Override
    public String settingsSnapshot(ActThemeSettings settings) {
        ObjectNode node = JsonSupport.blankObject();
        JsonSupport.putInt(node, "totalDrillCount", settings.getTotalDrillCount());
        JsonSupport.putInt(node, "perSceneLimit", settings.getPerSceneLimit());
        JsonSupport.putInt(node, "joinMode", settings.getJoinMode());
        JsonSupport.putInt(node, "trainingModeEnabled", settings.getTrainingModeEnabled());
        JsonSupport.putInt(node, "assessmentModeEnabled", settings.getAssessmentModeEnabled());
        JsonSupport.putInt(node, "aiResponseMode", settings.getAiResponseMode());
        JsonSupport.putInt(node, "coachReviewEnabled", settings.getCoachReviewEnabled());
        JsonSupport.putInt(node, "suggestionEnabled", settings.getSuggestionEnabled());
        JsonSupport.putText(node, "dialogModes", settings.getDialogModes());
        JsonSupport.putText(node, "practiceMethods", settings.getPracticeMethods());
        JsonSupport.putInt(node, "difficulty", settings.getDifficulty());
        JsonSupport.putInt(node, "allowCustomDifficulty", settings.getAllowCustomDifficulty());
        JsonSupport.putInt(node, "faceCheckBeforeEnabled", settings.getFaceCheckBeforeEnabled());
        JsonSupport.putInt(node, "faceCheckOngoingEnabled", settings.getFaceCheckOngoingEnabled());
        JsonSupport.putInt(node, "faceCheckIntervalSeconds", settings.getFaceCheckIntervalSeconds());
        JsonSupport.putText(node, "screenSwitchMode", settings.getScreenSwitchMode());
        JsonSupport.putInt(node, "screenSwitchLimit", settings.getScreenSwitchLimit());
        JsonSupport.putInt(node, "scenePassType", settings.getScenePassType());
        JsonSupport.putInt(node, "scenePassCount", settings.getScenePassCount());
        JsonSupport.putInt(node, "minTrainCountToUnlockExam", settings.getMinTrainCountToUnlockExam());
        JsonSupport.putInt(node, "maxExamCount", settings.getMaxExamCount());
        putTime(node, "planStartTime", settings.getPlanStartTime());
        putTime(node, "planEndTime", settings.getPlanEndTime());
        return JsonSupport.toJson(node);
    }

    @Override
    public String scenarioSnapshot(String themeId, int contentVersionNo, String scenarioUnitId) {
        ObjectNode root = JsonSupport.blankObject();
        ActScenarioUnit unit = unitMapper.selectById(scenarioUnitId);
        if (unit == null) {
            log.warn("场景快照缺失：themeId={} version={} unitId={}", themeId, contentVersionNo, scenarioUnitId);
            return JsonSupport.toJson(root);
        }
        ObjectNode scenario = root.putObject("scenario");
        JsonSupport.putText(scenario, "id", unit.getId());
        JsonSupport.putText(scenario, "name", unit.getUnitName());
        JsonSupport.putText(scenario, "audience", unit.getAudience());
        JsonSupport.putInt(scenario, "timeLimitMinutes", unit.getTimeLimitMinutes());
        JsonSupport.putText(scenario, "businessGoal", unit.getBusinessGoal());
        JsonSupport.putText(scenario, "businessImpact", unit.getBusinessImpact());
        JsonSupport.putText(scenario, "failureCost", unit.getFailureCost());
        JsonSupport.putText(scenario, "coreConflict", unit.getCoreConflict());
        JsonSupport.putValue(scenario, "icebergElements", unit.getIcebergElements());
        JsonSupport.putValue(scenario, "commonFailureModes", unit.getCommonFailureModes());
        JsonSupport.putValue(scenario, "difficulties", unit.getDifficulties());
        JsonSupport.putValue(scenario, "forbiddenBehaviors", unit.getForbiddenBehaviors());
        JsonSupport.putValue(scenario, "behaviorGoals", unit.getBehaviorGoals());
        JsonSupport.putInt(scenario, "passScore", unit.getPassScore());
        JsonSupport.putText(scenario, "dimensionWeights", unit.getDimensionWeights());

        ArrayNode dimensions = root.putArray("dimensions");
        List<ActEvaluationDimension> rows = dimensionMapper.selectList(
                new LambdaQueryWrapper<ActEvaluationDimension>()
                        .eq(ActEvaluationDimension::getScenarioUnitId, scenarioUnitId)
                        .orderByAsc(ActEvaluationDimension::getSortOrder));
        for (ActEvaluationDimension row : rows) {
            ObjectNode dimension = dimensions.addObject();
            JsonSupport.putText(dimension, "id", row.getId());
            JsonSupport.putText(dimension, "name", row.getDimensionName());
            JsonSupport.putValue(dimension, "weight", row.getWeight());
            JsonSupport.putText(dimension, "description", row.getDimensionDesc());
            JsonSupport.putText(dimension, "excellentStandard", row.getExcellentStandard());
            JsonSupport.putText(dimension, "goodStandard", row.getGoodStandard());
            JsonSupport.putText(dimension, "improveStandard", row.getImproveStandard());
            JsonSupport.putInt(dimension, "sortOrder", row.getSortOrder());
        }
        return JsonSupport.toJson(root);
    }

    @Override
    public String productProfilesSnapshot(String themeId, int contentVersionNo, String scenarioUnitId) {
        ArrayNode array = JsonSupport.blankArray();
        List<String> linkedIds = scenarioProfileMapper.selectList(
                        new LambdaQueryWrapper<ActScenarioProductProfile>()
                                .eq(ActScenarioProductProfile::getScenarioUnitId, scenarioUnitId)
                                .orderByAsc(ActScenarioProductProfile::getSortOrder))
                .stream()
                .map(ActScenarioProductProfile::getProductProfileId)
                .toList();
        if (linkedIds.isEmpty()) {
            return JsonSupport.toJson(array);
        }
        List<ActProductProfile> profiles = profileMapper.selectList(
                new LambdaQueryWrapper<ActProductProfile>()
                        .eq(ActProductProfile::getThemeId, themeId)
                        .eq(ActProductProfile::getContentVersionNo, contentVersionNo)
                        .in(ActProductProfile::getId, linkedIds)
                        .orderByAsc(ActProductProfile::getSortOrder));
        for (ActProductProfile profile : profiles) {
            ObjectNode node = array.addObject();
            JsonSupport.putText(node, "profileKey", profile.getProfileKey());
            JsonSupport.putText(node, "profileName", profile.getProfileName());
            JsonSupport.putText(node, "category", profile.getCategory());
            JsonSupport.putText(node, "description", profile.getDescription());
            JsonSupport.putText(node, "detailMarkdown", profile.getDetailMarkdown());
            JsonSupport.putText(node, "customFields", profile.getCustomFields());
        }
        return JsonSupport.toJson(array);
    }

    @Override
    public String materialsSnapshot(String themeId, int contentVersionNo) {
        ArrayNode array = JsonSupport.blankArray();
        List<ActContentVersionMaterial> rows = materialMapper.selectList(
                new LambdaQueryWrapper<ActContentVersionMaterial>()
                        .eq(ActContentVersionMaterial::getThemeId, themeId)
                        .eq(ActContentVersionMaterial::getContentVersionNo, contentVersionNo)
                        .eq(ActContentVersionMaterial::getVisibility, VISIBILITY_STUDENT)
                        .orderByAsc(ActContentVersionMaterial::getSortOrder));
        for (ActContentVersionMaterial row : rows) {
            ObjectNode node = array.addObject();
            JsonSupport.putText(node, "materialId", row.getMaterialId());
            JsonSupport.putText(node, "name", row.getName());
            JsonSupport.putText(node, "fileType", row.getFileType());
            JsonSupport.putValue(node, "fileSize", row.getFileSize());
            JsonSupport.putText(node, "sourceType", row.getSourceType());
            JsonSupport.putText(node, "sourceId", row.getSourceId());
            JsonSupport.putText(node, "renderMode", row.getRenderMode());
            JsonSupport.putText(node, "previewFileId", row.getPreviewFileId());
        }
        return JsonSupport.toJson(array);
    }

    private void putTime(ObjectNode node, String field, LocalDateTime value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, DATE_TIME.format(value));
        }
    }
}
