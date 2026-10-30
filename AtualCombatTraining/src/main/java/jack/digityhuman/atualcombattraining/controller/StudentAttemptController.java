package jack.digityhuman.atualcombattraining.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.req.AttemptCreateReq;
import jack.digityhuman.atualcombattraining.dto.resp.AttemptResp;
import jack.digityhuman.atualcombattraining.dto.resp.PreflightResp;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActScenarioUnitMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.AttemptLifecycleService;
import jack.digityhuman.atualcombattraining.service.AttemptPreflightService;
import jack.digityhuman.atualcombattraining.service.AttemptScriptGenerationService;
import jack.digityhuman.atualcombattraining.service.DrillReportService;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 学员端练习 attempt 接口（/api/act/student/attempts，契约 03 §7 / 04 §学生端）
 * <p>
 * 权限口径：本应用暂无统一拦截（R-3），因此每个接口都显式取当前用户并校验归属；
 * JWT 复用 StaffManagement 签发的 token（act.auth.jwt-secret 共享密钥）。
 */
@RestController
@RequestMapping("/api/act/student/attempts")
@RequiredArgsConstructor
public class StudentAttemptController {

    private final AttemptLifecycleService lifecycleService;
    private final AttemptPreflightService preflightService;
    private final AttemptScriptGenerationService scriptGenerationService;
    private final DrillReportService drillReportService;
    private final CurrentUserService currentUserService;
    private final ActThemeMapper themeMapper;
    private final ActScenarioUnitMapper unitMapper;

    /**
     * 预检（只读）：返回是否可以开始，以及拒绝理由。
     * 前端在点"开始练习"前调用，不写任何数据。
     */
    @GetMapping("/preflight")
    public ApiResponse<PreflightResp> preflight(
            @RequestParam String themeId,
            @RequestParam String scenarioId,
            @RequestParam int studyMode,
            @RequestParam String practiceType,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        // 契约 04 API-A01：返回 allowed/requiredSteps/余量/checks（解释性接口，不写数据）
        String userId = requireUser(authorization);
        return ApiResponse.success(
                preflightService.inspect(userId, themeId, scenarioId, studyMode, practiceType));
    }

    /** 创建 attempt（冻结快照；案例练习直接 ready，随机/自定义进入 preparing） */
    @PostMapping
    public ApiResponse<AttemptResp> create(
            @RequestBody AttemptCreateReq req,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        if (req == null || req.getThemeId() == null || req.getScenarioId() == null) {
            throw new BizException("缺少主题或场景");
        }
        int studyMode = req.getStudyMode() == null ? 1 : req.getStudyMode();
        String practiceType = req.getPracticeType() == null ? "case" : req.getPracticeType();
        ActPracticeAttempt attempt = lifecycleService.create(userId, req.getThemeId(),
                req.getScenarioId(), studyMode, practiceType, req.getCustomConfigJson());
        // 随机/自定义：脚本由 AIGentService 异步生成（案例练习创建即 ready，不走这里）
        if ("preparing".equals(attempt.getStatus())) {
            scriptGenerationService.generateAsync(attempt.getId(), userId);
        }
        return ApiResponse.success(toResp(attempt));
    }

    /** attempt 详情（归属校验） */
    @GetMapping("/{attemptId}")
    public ApiResponse<AttemptResp> detail(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        return ApiResponse.success(toResp(lifecycleService.requireOwned(attemptId, userId)));
    }

    /** 正式开始（扣次数、写 deadline；幂等） */
    @PostMapping("/{attemptId}/start")
    public ApiResponse<AttemptResp> start(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        lifecycleService.start(attemptId, userId);
        return ApiResponse.success(toResp(lifecycleService.requireOwned(attemptId, userId)));
    }

    /**
     * 主动结束（出报告，但 passed=false，D21）。
     * 到时由服务端任务调用同一入口（bySystem 分支）。
     */
    @PostMapping("/{attemptId}/finish")
    public ApiResponse<AttemptResp> finish(
            @PathVariable String attemptId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        ActPracticeAttempt attempt = lifecycleService.finish(attemptId, "manual_exit", false, userId);
        if ("report_generating".equals(attempt.getStatus())) {
            // 结束后异步生成报告（失败由补偿任务兜底，契约 08 §10）
            drillReportService.generateAsync(attemptId);
        }
        return ApiResponse.success(toResp(attempt));
    }

    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if ("0".equals(userId)) {
            throw new BizException("未获取到登录用户，请重新登录");
        }
        return userId;
    }

    private AttemptResp toResp(ActPracticeAttempt attempt) {
        AttemptResp resp = new AttemptResp();
        resp.setId(attempt.getId());
        resp.setThemeId(attempt.getThemeId());
        resp.setScenarioId(attempt.getScenarioId());
        resp.setStudyMode(attempt.getStudyMode());
        resp.setPracticeType(attempt.getPracticeType());
        resp.setStatus(attempt.getStatus());
        resp.setContentVersionNo(attempt.getContentVersionNo());
        resp.setPreparationStage(attempt.getPreparationStage());
        resp.setPreparationPercent(attempt.getPreparationPercent());
        resp.setCurrentRound(attempt.getCurrentRound());
        resp.setMaxRound(attempt.getMaxRound());
        resp.setTargetProgress(attempt.getTargetProgress());
        resp.setMaxDurationSeconds(attempt.getMaxDurationSeconds());
        resp.setStartedAt(attempt.getStartedAt());
        resp.setDeadlineAt(attempt.getDeadlineAt());
        resp.setEndedAt(attempt.getEndedAt());
        resp.setEndReason(attempt.getEndReason());
        resp.setTimedOut(attempt.getTimedOut());
        resp.setErrorCode(attempt.getErrorCode());
        resp.setErrorMessage(attempt.getErrorMessage());
        fillRuntimeContext(resp, attempt);
        return resp;
    }

    /**
     * 运行态只读上下文（契约 01 §F11 背景介绍侧栏）：全部取自 attempt 冻结快照，不读当前版本。
     * <p>
     * 脚本快照有两种形态：案例练习是 AI 案例正文的扁平字段（actorName / scriptBackground /
     * actorPersonality），随机与自定义练习是 drill-script-v1（scene / actor / userRole / goals）。
     * 两种都要读，缺什么就不下发什么，页面据此显示空态而不是造假内容。
     */
    private void fillRuntimeContext(AttemptResp resp, ActPracticeAttempt attempt) {
        ActTheme theme = themeMapper.selectById(attempt.getThemeId());
        if (theme != null) {
            resp.setThemeName(theme.getThemeName());
        }
        ActScenarioUnit unit = unitMapper.selectById(attempt.getScenarioId());
        if (unit != null) {
            resp.setScenarioName(unit.getUnitName());
            resp.setPassScore(unit.getPassScore());
            if (unit.getBusinessGoal() != null && !unit.getBusinessGoal().isBlank()) {
                resp.setBusinessGoal(unit.getBusinessGoal());
            }
        }
        ObjectNode scenario = JsonSupport.obj(attempt.getScenarioSnapshotJson());
        if (scenario != null) {
            ObjectNode node = JsonSupport.child(scenario, "scenario");
            if (node != null) {
                if (resp.getScenarioName() == null) {
                    resp.setScenarioName(JsonSupport.text(node, "name"));
                }
                fillScenarioDetail(resp, node);
                List<AttemptResp.Dimension> dimensions = scenarioDimensions(JsonSupport.children(node, "dimensions"));
                if (!dimensions.isEmpty()) {
                    resp.setDimensions(dimensions);
                }
            }
            if (resp.getDimensions() == null || resp.getDimensions().isEmpty()) {
                List<AttemptResp.Dimension> top = scenarioDimensions(JsonSupport.children(scenario, "dimensions"));
                if (!top.isEmpty()) {
                    resp.setDimensions(top);
                }
            }
        }

        ObjectNode script = JsonSupport.obj(attempt.getScriptSnapshotJson());
        if (script == null) {
            return;
        }
        if (resp.getScenarioName() == null) {
            resp.setScenarioName(JsonSupport.text(script, "scriptName"));
        }
        // 优先 drill-script-v1 嵌套结构，缺失回退案例练习的扁平字段。
        resp.setSceneNarration(firstText(script, script.path("scene").path("narration"), "scriptBackground"));
        if (resp.getSceneNarration() == null) {
            resp.setSceneNarration(JsonSupport.text(script, "sceneNarration"));
        }
        resp.setActorName(firstText(script, script.path("actor").path("name"), "actorName"));
        resp.setActorDesc(actorDesc(script));
        resp.setOpeningLine(firstText(script, script.path("scene").path("openingLine"), "scriptOpening"));
        resp.setUserRoleName(firstText(script, script.path("userRole").path("name"), null));
        resp.setUserRoleDesc(firstText(script, script.path("userRole").path("identity"), null));
        if (resp.getBusinessGoal() == null) {
            resp.setBusinessGoal(firstText(script, script.path("goals").path("practiceGoal"), null));
        }
        ArrayNode guidance = JsonSupport.children(JsonSupport.child(script, "goals"), "behaviorGuidance");
        resp.setBehaviorGuidance(behaviorGuidance(guidance));
        ObjectNode evaluation = JsonSupport.child(script, "evaluation");
        if (evaluation != null) {
            Integer passScore = JsonSupport.integer(evaluation, "passScore");
            if (passScore != null) {
                resp.setPassScore(passScore);
            }
            if (resp.getDimensions() == null || resp.getDimensions().isEmpty()) {
                List<AttemptResp.Dimension> dimensions =
                        scenarioDimensions(JsonSupport.children(evaluation, "dimensions"));
                if (!dimensions.isEmpty()) {
                    resp.setDimensions(dimensions);
                }
            }
        }
    }

    private void fillScenarioDetail(AttemptResp resp, ObjectNode node) {
        if (resp.getBusinessBackground() == null) {
            resp.setBusinessBackground(JsonSupport.text(node, "businessImpact"));
        }
        if (resp.getBusinessGoal() == null) {
            resp.setBusinessGoal(JsonSupport.text(node, "businessGoal"));
        }
        if (resp.getSceneNarration() == null) {
            resp.setSceneNarration(JsonSupport.text(node, "coreConflict"));
        }
        if (resp.getPassScore() == null) {
            resp.setPassScore(JsonSupport.integer(node, "passScore"));
        }
    }

    /** 维度摘要：名称 + 权重（侧栏「评估标准」只需这两列） */
    private List<AttemptResp.Dimension> scenarioDimensions(ArrayNode dimensions) {
        List<AttemptResp.Dimension> list = new ArrayList<>();
        if (dimensions == null) {
            return list;
        }
        for (JsonNode node : dimensions) {
            if (!node.isObject()) {
                continue;
            }
            AttemptResp.Dimension item = new AttemptResp.Dimension();
            item.setName(node.path("name").asText(null));
            if (node.hasNonNull("weight")) {
                item.setWeight(node.get("weight").decimalValue());
            }
            if (item.getName() != null) {
                list.add(item);
            }
        }
        return list;
    }

    /** AI 演员摘要：身份 + 性格/沟通风格，拼成一句副标题 */
    private String actorDesc(ObjectNode script) {
        String identity = firstText(script, script.path("actor").path("identity"), "actorPosition");
        String personality = firstText(script, script.path("actor").path("communicationStyle"), "actorPersonality");
        if (identity == null && personality == null) {
            return null;
        }
        if (identity == null) {
            return personality;
        }
        return personality == null ? identity : identity + "，" + personality;
    }

    private List<String> behaviorGuidance(ArrayNode guidance) {
        List<String> list = new ArrayList<>();
        if (guidance == null) {
            return list;
        }
        for (JsonNode node : guidance) {
            if (node.isTextual() && !node.asText().isBlank()) {
                list.add(node.asText());
            }
        }
        return list;
    }

    /** 优先取嵌套字段，缺失时回退扁平字段（案例练习快照形态） */
    private String firstText(ObjectNode root, JsonNode nested, String flatKey) {
        if (nested != null && nested.isTextual() && !nested.asText().isBlank()) {
            return nested.asText();
        }
        if (flatKey == null) {
            return null;
        }
        String value = JsonSupport.text(root, flatKey);
        return value == null || value.isBlank() ? null : value;
    }
}

