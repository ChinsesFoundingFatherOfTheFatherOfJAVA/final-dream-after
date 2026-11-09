package jack.digityhuman.atualcombattraining.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.req.AttemptCreateReq;
import jack.digityhuman.atualcombattraining.dto.resp.AttemptMessagesResp;
import jack.digityhuman.atualcombattraining.dto.resp.AttemptResp;
import jack.digityhuman.atualcombattraining.dto.resp.PreflightResp;
import jack.digityhuman.atualcombattraining.entity.ActAttemptMessage;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActScenarioUnit;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAttemptMessageMapper;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

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
    /** 聊天记录读取（2026-11-04 新增接口用；消息原本只由运行时服务写入） */
    private final ActAttemptMessageMapper messageMapper;

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
    /**
     * 创建 attempt（冻结快照）。
     * <p>
     * 2026-11-04 改：脚本来源分流——case 走发布快照、random 走发布预生成<b>取档</b>（创建即 ready，
     * 不调模型）、custom 才进 preparing 等异步生成。{@code reuseFromAttemptId} 非空表示
     * 「再练一次」，直接沿用上次快照，不取档（保证不换脸）。
     */
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
                req.getScenarioId(), studyMode, practiceType, req.getCustomConfigJson(),
                req.getReuseFromAttemptId(), req.getDifficulty());
        // 只有自定义练习仍需要现场生成脚本（case/random 创建时已 ready）
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

    /**
     * 消息历史（契约 04 §API-A05：`GET /student/attempts/{attemptId}/messages?afterSeq=0&limit=200`）。
     * <p>
     * 运行态页刷新/重连靠它恢复对话——消息一直落在 act_attempt_message（user/assistant/coach），
     * 之前只是没暴露读接口。契约要求「先调本接口，再连接 WS」，所以同时下发**轮次状态**，
     * 前端不必靠内存恢复进度。
     * <p>
     * {@code afterSeq}：只取 seq 大于它的消息（0 = 从头）；{@code limit}：单次上限（默认 200，
     * 硬上限 500，防止一次拉爆）。归属校验经 attempt，越权一律拒绝。只读，不改 WS 帧名与事件名。
     */
    @GetMapping("/{attemptId}/messages")
    public ApiResponse<AttemptMessagesResp> messages(
            @PathVariable String attemptId,
            @RequestParam(defaultValue = "0") long afterSeq,
            @RequestParam(defaultValue = "200") int limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String userId = requireUser(authorization);
        // 归属校验先行：不是自己的 attempt 一律拒绝，不泄露对话内容
        ActPracticeAttempt attempt = lifecycleService.requireOwned(attemptId, userId);
        int effectiveLimit = Math.max(1, Math.min(limit, 500));

        Long totalCount = messageMapper.selectCount(
                new LambdaQueryWrapper<ActAttemptMessage>()
                        .eq(ActAttemptMessage::getAttemptId, attempt.getId()));
        List<ActAttemptMessage> rows = messageMapper.selectList(
                new LambdaQueryWrapper<ActAttemptMessage>()
                        .eq(ActAttemptMessage::getAttemptId, attempt.getId())
                        .gt(ActAttemptMessage::getSeq, afterSeq)
                        .orderByAsc(ActAttemptMessage::getSeq)
                        .last("LIMIT " + effectiveLimit));

        AttemptMessagesResp resp = new AttemptMessagesResp();
        resp.setAttemptId(attempt.getId());
        resp.setTotal(rows.size());
        resp.setTotalCount(totalCount == null ? 0 : totalCount);
        resp.setAfterSeq(afterSeq);
        resp.setMessages(rows.stream().map(row -> {
            AttemptMessagesResp.Message item = new AttemptMessagesResp.Message();
            item.setId(row.getId());
            item.setSeq(row.getSeq());
            item.setRole(row.getRole());
            item.setMessageType(row.getMessageType());
            item.setTextContent(row.getTextContent());
            // 教练点评单独标记，前端不必自己再判 role 一次
            item.setCoach("coach".equals(row.getRole()));
            item.setCreatedAt(row.getCreatedAt());
            return item;
        }).toList());
        resp.setTurn(turnStateOf(attempt));
        return ApiResponse.success(resp);
    }

    /** 轮次状态（契约 API-A05「返回消息和轮次状态」）：全部取自 attempt 行，不猜 */
    private AttemptMessagesResp.TurnState turnStateOf(ActPracticeAttempt attempt) {
        AttemptMessagesResp.TurnState turn = new AttemptMessagesResp.TurnState();
        turn.setCurrentRound(attempt.getCurrentRound());
        turn.setMaxRound(attempt.getMaxRound());
        turn.setTargetProgress(attempt.getTargetProgress());
        turn.setStatus(attempt.getStatus());
        // 剩余秒数只在"进行中"有意义：未开始没有 deadlineAt，已结束再看剩余也是负数
        if ("in_progress".equals(attempt.getStatus()) && attempt.getDeadlineAt() != null) {
            long seconds = java.time.Duration.between(
                    LocalDateTime.now(), attempt.getDeadlineAt()).getSeconds();
            turn.setRemainingSeconds((int) Math.max(0, seconds));
        }
        return turn;
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
     * actorPersonality / userRoleName / userRolePosition / userRoleBackground，后三个是 case-gen v2
     * 的可选字段），随机与自定义练习是 drill-script-v1（scene / actor / userRole / goals）。
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
        resp.setUserRoleName(firstText(script, script.path("userRole").path("name"), "userRoleName"));
        // userRoleDesc 是旧口径（身份摘要），新页面用 userRoleIdentity；两处都跟上案例侧的 userRolePosition
        resp.setUserRoleDesc(firstText(script, script.path("userRole").path("identity"), "userRolePosition"));
        fillRoleDetails(resp, script);
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

    /**
     * 角色细分字段（R6-1 背景介绍细化，2026-11-04 新增；2026-11-05 补案例侧 userRole）。
     * <p>
     * 两种快照形态各读各的，<b>字段不互相顶替</b>：
     * <ul>
     *   <li><b>random/custom</b>（drill-script-v1）：userRole 有 name/identity/ageRange/background，
     *       actor 有 name/identity/ageRange/personality[]/communicationStyle + 隐式目标/隐藏事实；</li>
     *   <li><b>case</b>（案例正文扁平字段）：actor 侧十个字段 + <b>case-gen v2 新增的三个可选字段</b>
     *       userRoleName / userRolePosition / userRoleBackground。它们<b>可选</b>：模型没给就是 null，
     *       前端回落「学员」兜底，不能因此报错或编造。</li>
     * </ul>
     * 案例侧口径映射：userRolePosition → userRoleIdentity（「身份摘要」是给页面副标题用的，
     * 岗位正是学员读得懂的身份）；userRoleAge 案例侧无数据源，保持 null（不拿岗位顶）。
     * <p>
     * 取不到一律留 null，<b>不用 businessBackground/scriptBackground/场景旁白顶替 userRoleBackground</b>
     * —— 那段已经进了 sceneNarration，顶替会让同一段场景描述变成两处「人物小传」。
     */
    private void fillRoleDetails(AttemptResp resp, ObjectNode script) {
        // ---- 我的角色 ----
        if (resp.getUserRoleIdentity() == null) {
            // drill-script-v1 用 userRole.identity；案例侧用 userRolePosition（岗位即身份摘要）
            resp.setUserRoleIdentity(firstText(script,
                    script.path("userRole").path("identity"), "userRolePosition"));
        }
        if (resp.getUserRoleAge() == null) {
            resp.setUserRoleAge(firstText(script, script.path("userRole").path("ageRange"), null));
        }
        if (resp.getUserRoleBackground() == null) {
            // 案例侧取 userRoleBackground；绝不用 scriptBackground（它就是 sceneNarration 的来源）
            resp.setUserRoleBackground(firstText(script, script.path("userRole").path("background"),
                    "userRoleBackground"));
        }

        // ---- AI 演员 ----
        if (resp.getActorIdentity() == null) {
            // drill-script-v1 用 identity；案例正文只有 actorPosition（岗位）
            resp.setActorIdentity(firstText(script, script.path("actor").path("identity"), "actorPosition"));
        }
        if (resp.getActorAge() == null) {
            resp.setActorAge(firstText(script, script.path("actor").path("ageRange"), null));
        }
        if (resp.getActorCompany() == null) {
            // 只有案例正文有公司；drill-script-v1 的 actor 没有 company 字段
            resp.setActorCompany(firstText(script, null, "actorCompany"));
        }
        if (resp.getActorPersonality() == null) {
            // 案例是字符串；drill-script-v1 是数组，用「、」拼接成一句
            String personality = firstText(script, null, "actorPersonality");
            if (personality == null) {
                personality = joinText(JsonSupport.children(JsonSupport.child(script, "actor"), "personality"));
            }
            resp.setActorPersonality(personality);
        }
        if (resp.getActorBackground() == null) {
            resp.setActorBackground(actorBackgroundOf(script));
        }
        if (resp.getActorAvatarUrl() == null) {
            // 形象 URL（2026-11-05 新增）：发布期已固化进快照，运行时只读不算。
            // drill-script-v1 读 actor.avatarUrl；案例正文读扁平 actorAvatarUrl。
            // 取不到留 null（前端首字占位），绝不拿素材池现算——那会让"重练沿用同图"依赖运行时输入。
            resp.setActorAvatarUrl(firstText(script,
                    script.path("actor").path("avatarUrl"), "actorAvatarUrl"));
        }
    }

    /**
     * AI 演员人物背景：优先 actor.background（drill-script-v1 若有），
     * 否则用隐式目标+隐藏事实拼成一句人物小传（这两组字段本来就是角色的"内情"）。
     * 案例正文没有人物小传，只有 scriptBackground（场景背景）——那个已进 sceneNarration，
     * 不再重复塞给人物背景，避免同一段文字出现两次。
     */
    private String actorBackgroundOf(ObjectNode script) {
        ObjectNode actor = JsonSupport.child(script, "actor");
        if (actor == null) {
            return null;
        }
        String background = JsonSupport.text(actor, "background");
        if (background != null && !background.isBlank()) {
            return background;
        }
        List<String> parts = new ArrayList<>();
        parts.addAll(JsonSupport.strings(JsonSupport.children(actor, "implicitGoals")));
        parts.addAll(JsonSupport.strings(JsonSupport.children(actor, "hiddenFacts")));
        List<String> cleaned = parts.stream()
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .toList();
        return cleaned.isEmpty() ? null : String.join("；", cleaned);
    }

    /** 字符串数组按「、」拼接（personality 这类标签用它拼成一句） */
    private String joinText(ArrayNode array) {
        List<String> items = JsonSupport.strings(array).stream()
                .filter(text -> text != null && !text.isBlank())
                .toList();
        return items.isEmpty() ? null : String.join("、", items);
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

