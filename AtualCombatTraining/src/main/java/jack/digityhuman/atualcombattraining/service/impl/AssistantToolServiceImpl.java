package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.req.ArtifactPatchesReq;
import jack.digityhuman.atualcombattraining.dto.req.CreateTopicReq;
import jack.digityhuman.atualcombattraining.dto.req.GenerateCasesReq;
import jack.digityhuman.atualcombattraining.dto.req.GenerateThemeReq;
import jack.digityhuman.atualcombattraining.dto.resp.AcceptedResp;
import jack.digityhuman.atualcombattraining.dto.resp.CreateTopicResp;
import jack.digityhuman.atualcombattraining.dto.resp.UpdateArtifactResp;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.ArtifactSupport;
import jack.digityhuman.atualcombattraining.service.AssistantContextService;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantMaterialService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.AssistantToolService;
import jack.digityhuman.atualcombattraining.service.CaseGenerationService;
import jack.digityhuman.atualcombattraining.service.ThemeGenerationService;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Dify 工具端点业务编排实现（FP-35/39）
 * <p>
 * 设计要点：Dify 永不直接写库——工具端点执行业务、主动推 WS 事件，再把工具结果返回给模型。
 * create_topic 建的是"草稿"主题（FP-39 明确无需用户确认），列表页立即可见，
 * 并同时建版本 1 行与默认主题设置，保证主题设置页在前端可正常打开。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantToolServiceImpl implements AssistantToolService {

    /** 主题名称列宽 */
    private static final int LEN_THEME_NAME = 50;
    /** 主题描述列宽 */
    private static final int LEN_THEME_DESC = 100;
    /** 草稿主题状态 */
    private static final int STATUS_DRAFT = 0;
    /** 默认难度：2 正常 */
    private static final int DEFAULT_DIFFICULTY = 2;
    /** 默认练习方式（全选） */
    private static final String DEFAULT_PRACTICE_METHODS = "case,custom,random";
    /** 版本创建者 */
    private static final String CREATOR = "管理员";
    /** 占位负责人 */
    private static final String OWNER_USER_ID = "admin";

    /**
     * 生成类工具的返回提示（会作为工具观察结果进入模型下一轮上下文，属软约束）。
     * 用于抑制两种实测见过的偏差行为：越过用户确认直接生成、受理后重复调用同一工具。
     * 真正的闸门仍在编排智能体的提示词里，这里只是加一道"看得见的提醒"。
     */
    private static final String GENERATE_TOOL_HINT =
            "已受理。注意：本工具仅在用户明确确认方案后才应调用；受理后请勿重复调用，"
                    + "生成结果会以事件推送到右侧面板，请在回复中只说明\"正在生成，请稍候\"，不要编造结果。";

    private final AssistantSessionService sessionService;
    private final ArtifactService artifactService;
    private final AssistantEventPublisher eventPublisher;
    private final ThemeGenerationService themeGenerationService;
    private final CaseGenerationService caseGenerationService;
    private final AssistantMaterialService materialService;
    private final AssistantContextService contextService;
    private final AssistantRoundRegistry roundRegistry;
    private final ActThemeMapper themeMapper;
    private final ActThemeVersionMapper versionMapper;

    /**
     * create_topic：无主题则建草稿（含默认设置与版本 1）、回填会话 theme_id；
     * 已有主题则改名/改描述（不重复建）。随后推 topic-changed 让右面板展开。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CreateTopicResp createTopic(String traceId, String sessionId, CreateTopicReq req) {
        String resolvedSessionId = resolveSessionId(traceId, sessionId);
        ActAiSession session = sessionService.requireSession(resolvedSessionId);
        if (req == null || req.getTopic() == null || req.getTopic().isBlank()) {
            throw new BizException("topic 不能为空");
        }
        String topic = ArtifactSupport.truncate(req.getTopic().trim(), LEN_THEME_NAME);
        String description = ArtifactSupport.truncate(req.getDescription(), LEN_THEME_DESC);

        String themeId = session.getThemeId();
        boolean created = false;
        LocalDateTime now = LocalDateTime.now();
        if (themeId == null || themeId.isBlank()) {
            ActTheme theme = buildDraftTheme(topic, description, resolvedSessionId, now);
            themeMapper.insert(theme);
            // 契约 03 §4.2：未发布主题的工作内容是隐藏 v0，首次发布时从它复制出公开 v1。
            // 2026-11-05 修：新建主题曾只插 v1 行，导致首次发布 requireVersion(working=0) 直接抛
            // 「内容版本不存在 version=0」，发布永远失败（端到端验证发现，与 userRole 无关）。
            // 两行都插：v0 供 ContentVersionServiceImpl#firstPublish 取源，v1 供设置页直读。
            versionMapper.insert(buildInitialVersion(theme.getId(), 0, topic, description, now));
            versionMapper.insert(buildInitialVersion(theme.getId(), 1, topic, description, now));
            themeId = theme.getId();
            sessionService.bindTheme(resolvedSessionId, themeId);
            created = true;
            log.info("create_topic 新建草稿主题 themeId={} sessionId={} topic={}",
                    themeId, resolvedSessionId, topic);
        } else {
            updateThemeMeta(themeId, topic, description, now);
            log.info("create_topic 更新主题名称/描述 themeId={} topic={}", themeId, topic);
        }
        // 产物工作区先建好（右面板有数据源，后续生成直接写它）
        artifactService.ensureWorking(themeId);
        // 建主题前上传的材料在此归集：回填 theme_id 并追加进 artifact.materials（FP-42）
        materialService.linkSessionMaterials(resolvedSessionId, themeId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("explicit", true);
        params.put("topic", topic);
        params.put("description", description == null ? "" : description);
        eventPublisher.push(resolvedSessionId, traceId, BizTypes.TOPIC_CHANGED, params);

        CreateTopicResp resp = new CreateTopicResp();
        resp.setThemeId(themeId);
        resp.setCreated(created);
        return resp;
    }

    /** update_artifact：full_replace 补丁应用到工作区 → 推 artifact-updated（携带补丁供前端局部更新） */
    @Override
    public UpdateArtifactResp updateArtifact(String traceId, String sessionId, ArtifactPatchesReq req) {
        String resolvedSessionId = resolveSessionId(traceId, sessionId);
        ActAiSession session = sessionService.requireSession(resolvedSessionId);
        String themeId = requireThemeId(session);
        if (req == null || req.getPatches() == null) {
            throw new BizException("patches 不能为空");
        }
        // 跨 HTTP 边界的 patches 是 Object（List<Map>），此处转成内部树模型
        ArrayNode patches = JsonSupport.toArray(req.getPatches());
        if (patches.isEmpty()) {
            throw new BizException("patches 不能为空");
        }
        int versionNo = artifactService.applyFullReplacePatches(themeId, patches);
        ActAiArtifact working = artifactService.findWorking(themeId);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", working == null ? null : working.getId());
        params.put("version", String.valueOf(versionNo));
        params.put("content", JsonSupport.plainNode(patches));
        eventPublisher.push(resolvedSessionId, traceId, BizTypes.ARTIFACT_UPDATED, params);

        UpdateArtifactResp resp = new UpdateArtifactResp();
        resp.setVersionNo(String.valueOf(versionNo));
        return resp;
    }

    @Override
    public AcceptedResp generateTheme(String traceId, String sessionId, GenerateThemeReq req) {
        String resolvedSessionId = resolveSessionId(traceId, sessionId);
        // 材料摘要以库里的解析结果为准。模型常把该参数留空，随后就声称“材料未解析”。
        if (req != null) {
            String stored = contextService.materialSummary(sessionService.requireSession(resolvedSessionId));
            if (!stored.isBlank()) {
                req.setMaterialSummary(stored);
            }
        }
        themeGenerationService.accept(resolvedSessionId, traceId, req);
        return AcceptedResp.of(GENERATE_TOOL_HINT);
    }

    @Override
    public AcceptedResp generateCases(String traceId, String sessionId, GenerateCasesReq req) {
        String resolvedSessionId = resolveSessionId(traceId, sessionId);
        caseGenerationService.accept(resolvedSessionId, traceId, req);
        return AcceptedResp.of(GENERATE_TOOL_HINT);
    }

    /**
     * 定位会话。
     * <p>
     * 优先按 traceId 定位：traceId 由本轮对话注入（DSL 里绑定 sys.trace_id），是权威线索；
     * 工具入参里的 sessionId 可能来自模型自填（DSL 把该参数设为 auto 时模型会编一个），
     * 因此**只在 traceId 定位不到时才回退**，且回退时必须校验该会话真实存在。
     * 这样既不会被编造的 ID 带偏，也保留了"工具端点仅传 sessionId"的兼容性。
     */
    private String resolveSessionId(String traceId, String sessionId) {
        String byTrace = roundRegistry.sessionIdOf(traceId);
        if (byTrace != null && !byTrace.isBlank()) {
            if (sessionId != null && !sessionId.isBlank() && !byTrace.equals(sessionId)) {
                log.warn("工具入参 sessionId 与本轮 traceId 映射不一致，以 traceId 为准：traceId={} 入参={} 实际={}",
                        traceId, sessionId, byTrace);
            }
            return byTrace;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            // 校验存在性：不存在的会话（模型编造）会抛「会话不存在」，避免后续写入到错误会话
            sessionService.requireSession(sessionId);
            log.warn("按 traceId 未定位到会话，改用工具入参 sessionId={}", sessionId);
            return sessionId;
        }
        throw new BizException("无法定位会话（traceId 无对应轮次，且未传有效的 sessionId）");
    }

    /** 会话必须已绑定主题 */
    private String requireThemeId(ActAiSession session) {
        String themeId = session.getThemeId();
        if (themeId == null || themeId.isBlank()) {
            // 与 generate_theme / generate_cases 保持一致的"可执行"报错：告知下一步该做什么，
            // 便于模型自我纠正（实测模型在无主题时会盲目调用写类工具，只回"请先创建主题"它不知道该怎么办）。
            throw new BizException("当前会话还没有主题：请先调用 create_topic 创建草稿主题后再调用本工具；"
                    + "另外，用户上传的材料已由服务端自动写入产物，无需用本工具重复写入");
        }
        return themeId;
    }

    /** 构造草稿主题行（默认主题设置：joinMode=1/difficulty=2/practiceMethods 全选） */
    private ActTheme buildDraftTheme(String topic, String description, String sessionId, LocalDateTime now) {
        ActTheme theme = new ActTheme();
        theme.setThemeName(topic);
        theme.setThemeDesc(description);
        theme.setStatus(STATUS_DRAFT);
        theme.setThemeMode(1);
        theme.setAssignMode(1);
        theme.setDifficulty(DEFAULT_DIFFICULTY);
        theme.setSourceType(1);
        theme.setCurrentVersionNo(1);
        theme.setPassRate(BigDecimal.ZERO);
        theme.setPassUserCount(0);
        theme.setParticipantCount(0);
        theme.setAvgScore(BigDecimal.ZERO);
        theme.setTotalTrainingCount(0);
        theme.setCustomerProfileCount(0);
        theme.setConversationId(sessionId);
        theme.setCreatedAt(now);
        theme.setUpdatedAt(now);
        return theme;
    }

    /** 构造版本行（默认设置，主题设置页可直接读取）；versionNo=0 是隐藏工作快照，1 是 legacy 可见行 */
    private ActThemeVersion buildInitialVersion(String themeId, int versionNo, String topic,
                                                String description, LocalDateTime now) {
        ActThemeVersion version = new ActThemeVersion();
        version.setThemeId(themeId);
        version.setVersionNo(versionNo);
        version.setThemeName(topic);
        version.setThemeDesc(description);
        version.setOwnerUserId(OWNER_USER_ID);
        version.setTotalDrillCount(1);
        version.setJoinMode(1);
        version.setGuestAccessEnabled(0);
        version.setAiResponseMode(1);
        version.setTrainingModeEnabled(1);
        version.setAssessmentModeEnabled(0);
        version.setCoachReviewEnabled(1);
        version.setSuggestionEnabled(0);
        version.setPracticeMethods(DEFAULT_PRACTICE_METHODS);
        version.setDifficulty(DEFAULT_DIFFICULTY);
        version.setAllowCustomDifficulty(1);
        version.setFaceCheckEnabled(0);
        version.setScreenSwitchMode("forbid");
        version.setScreenSwitchLimit(0);
        version.setScenePassType(1);
        version.setScenePassCount(1);
        version.setCreator(CREATOR);
        version.setUsedUserCount(0);
        version.setCompletedCount(0);
        version.setInProgressCount(0);
        version.setCreatedAt(now);
        version.setUpdatedAt(now);
        return version;
    }

    /** 更新主题名称/描述（主题表冗余 + 当前版本行同步，保证设置页读到新值） */
    private void updateThemeMeta(String themeId, String topic, String description, LocalDateTime now) {
        ActTheme theme = themeMapper.selectById(themeId);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        ActTheme themeUpdate = new ActTheme();
        themeUpdate.setId(themeId);
        themeUpdate.setThemeName(topic);
        themeUpdate.setThemeDesc(description);
        themeUpdate.setUpdatedAt(now);
        themeMapper.updateById(themeUpdate);

        ActThemeVersion current = versionMapper.selectOne(new LambdaQueryWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getThemeId, themeId)
                .eq(ActThemeVersion::getVersionNo, theme.getCurrentVersionNo())
                .last("LIMIT 1"));
        if (current != null) {
            ActThemeVersion versionUpdate = new ActThemeVersion();
            versionUpdate.setId(current.getId());
            versionUpdate.setThemeName(topic);
            versionUpdate.setThemeDesc(description);
            versionUpdate.setUpdatedAt(now);
            versionMapper.updateById(versionUpdate);
        }
    }
}