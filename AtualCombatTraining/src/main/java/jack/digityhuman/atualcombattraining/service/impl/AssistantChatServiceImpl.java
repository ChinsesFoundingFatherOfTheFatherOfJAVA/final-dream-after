package jack.digityhuman.atualcombattraining.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.AssistantActions;
import jack.digityhuman.atualcombattraining.common.AssistantIdentity;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dify.DifyAgentClient;
import jack.digityhuman.atualcombattraining.dify.DifyEventTranslator;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiMessage;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantChatService;
import jack.digityhuman.atualcombattraining.service.AssistantContextService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.ConfirmService;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundContext;
import jack.digityhuman.atualcombattraining.websocket.AssistantRoundRegistry;
import jack.digityhuman.atualcombattraining.websocket.WsFrameEncoder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WS 聊天主链路实现（02 方案 §5.1）
 * <p>
 * 分流规则（03 文档 §3.2 的 action 定义，常量见 {@link jack.digityhuman.atualcombattraining.common.AssistantActions}）：
 * <ul>
 *   <li>biz-side-manage-confirm → 走确认落库，不进 Dify；</li>
 *   <li>biz-side-manage-update-artifact → 前端结构性小改动（材料「学员可见」开关）→ 更新 artifact + 推 artifact-updated，不进 Dify；</li>
 *   <li>common-session-start → 连上后自动发送的握手 action，用它触发编排智能体的开场白；</li>
 *   <li>其余（text/file 块）→ 拼装上下文与 query 调编排智能体。</li>
 * </ul>
 * 事件序列由本轮上下文（{@link AssistantRoundContext}）统一产出：
 * ack → session-start → message-extra-info → message-start → N×(part) → session-finish；
 * 助手消息内容在轮次结束时整体落库（含工具端点期间推入的 data 事件块）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatServiceImpl implements AssistantChatService {

    /**
     * 会话开始（action 帧）时补的 kickoff 文本——**新会话**（无主题、无历史）。
     * <p>
     * action 帧本身没有正文，但编排智能体需要一个 query 才会开口，所以由服务端补一句。
     * 文案必须明确"只打招呼、先问背景、不要调用任何工具"：
     * 早期写成"开始会话"时，模型把"开始"读成了"开始生成"，开场白的同时就调用了 generate_theme，
     * 而那一刻主题还没建，工具被服务端按规则拒绝（"请先创建主题再生成内容"），表现为一进页面就报错。
     */
    private static final String SESSION_START_QUERY_NEW =
            "（会话刚开始，用户还没有说话）请先向用户打招呼，并询问：所属行业与业务模式、主要训练对象、"
                    + "本次培训核心目标。注意：此阶段不要调用任何工具；等用户给出明确方向后，"
                    + "也需先输出场景规划方案并取得用户明确确认，才可调用生成类工具。";

    /**
     * 会话开始（action 帧）时补的 kickoff 文本——**回到已有主题的会话**（有主题或已有历史）。
     * <p>
     * 这种场景下不能再说"会话刚开始、请询问行业/对象/目标"：上下文里 theme_status 已不是 new、
     * artifact_summary 也非空，两句话互相矛盾，容易让模型重复追问已经提供过的信息、
     * 或误判自己处于"背景了解"阶段。因此改为"简述进展 + 问用户本次想做什么"，
     * 并且同样强调"不要主动生成、要等明确确认"。
     */
    private static final String SESSION_START_QUERY_RESUMED =
            "（用户重新进入了这个已有主题的会话；请以 theme_status 与 artifact_summary 为准，"
                    + "不要再重复询问已经提供过的信息）请简要说明当前进展（已有哪些场景/画像/维度/案例），"
                    + "并询问用户本次想做什么调整。注意：不要主动调用生成类工具，"
                    + "除非用户明确要求生成或修改内容；若要生成，先给出方案并等用户确认。";
    /** 纯文件消息时的 query 提示后缀 */
    private static final String FILE_ONLY_QUERY_SUFFIX = "，请解析材料并给出设计建议";
    /** 无任何可用文本时的兜底 query */
    private static final String FALLBACK_QUERY = "请继续";

    private final AssistantSessionService sessionService;
    private final AssistantContextService contextService;
    private final AssistantRoundRegistry roundRegistry;
    private final DifyAgentClient agentClient;
    private final ConfirmService confirmService;
    private final ArtifactService artifactService;

    /**
     * 每个会话**只受理第一次** common-session-start（方案 B，2026-09-20 用户确认）。
     * <p>
     * 理由：前端会在重连/重复挂载时反复发这个握手帧（实测同一会话 3 分钟内发了 3 次），
     * 每次都真的去调 Dify 会出现"两条开场白"，且第二次会因主题刚被创建而走"回到已有会话"分支，
     * 表现为用户看到"凭空出现的进展/主题"。因此约定：**一个会话只由第一次握手触发开场白**，
     * 之后重复到达一律只回 ack + session-finish 收尾，不再调用智能体。
     * <p>
     * 取舍：重进页面不再有"进展问候"（只恢复历史消息 + 右面板按 artifact 呈现）；
     * 记录在进程内存里，服务重启后同一会话会再问候一次（无害）。
     */
    private static final int SESSION_START_MAX_TRACKED = 5000;
    /** 已发送过开场白的会话（内存态；add 返回 false 即表示已处理过） */
    private final java.util.Set<String> sessionStartDone =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    /**
     * 记录并判断本次 session-start 是否应当受理：该会话首次到达返回 true，之后返回 false
     * （调用方只做收尾、不调智能体）。
     */
    private boolean markSessionStart(String sessionId) {
        if (sessionStartDone.size() > SESSION_START_MAX_TRACKED) {
            // 极简容量保护：超过上限整体清空（老会话若再来一次问候也无害）
            sessionStartDone.clear();
        }
        return sessionStartDone.add(sessionId);
    }

    @Override
    public void handleFrame(String sessionId, String rawFrame) {
        ObjectNode frame;
        try {
            frame = JsonSupport.obj(rawFrame);
        } catch (Exception e) {
            log.warn("WS 帧非法 JSON，忽略 sessionId={}", sessionId);
            return;
        }
        if (frame == null || !"chat".equals(JsonSupport.text(frame, "type"))) {
            return;
        }
        ObjectNode message = JsonSupport.child(JsonSupport.child(frame, "payload"), "message");
        if (message == null) {
            log.warn("WS chat 帧缺少 payload.message，忽略 sessionId={}", sessionId);
            return;
        }
        String traceId = JsonSupport.text(frame, "traceId");
        String messageId = JsonSupport.text(message, "id");
        ArrayNode content = JsonSupport.children(message, "content");
        // 1. 用户消息落库（先落库后回 ack，保证 ack 里的 dbId 可用）
        ActAiMessage stored = sessionService.appendUserMessage(
                sessionId, messageId, JsonSupport.toJson(content), traceId);

        AssistantRoundContext context = roundRegistry.open(sessionId, traceId);
        try {
            // 2. 回 user-message-ack
            context.ack(stored.getDbId(), stored.getId());
            // 3. 分流
            ObjectNode action = firstActionBlock(content);
            String actionName = action == null ? null : JsonSupport.text(action, "actionName");
            if (AssistantActions.CONFIRM.equals(actionName)) {
                handleConfirm(sessionId, context, action);
                return;
            }
            if (AssistantActions.isFrontendUpdate(actionName)) {
                handleUpdateArtifact(sessionId, context, action);
                return;
            }
            // 握手帧去重：同一个会话在短时间内重复收到 common-session-start（页面重复挂载、断线重连、
            // 卡顿后前端重试等）时只保留一次，否则会出现"两条开场白"，并且第二次会因主题刚被创建而
            // 走"回到已有会话"分支，表现为用户看到的"凭空出现的进展/主题"。
            if (AssistantActions.SESSION_START.equals(actionName) && !markSessionStart(sessionId)) {
                log.warn("该会话已发送过开场白，重复的 common-session-start 已忽略，sessionId={}", sessionId);
                context.finish();
                return;
            }
            handleAgentChat(sessionId, traceId, context, content, actionName);
        } catch (Exception e) {
            log.error("聊天主链路处理失败 sessionId={} traceId={}", sessionId, traceId, e);
            safeFailAndFinish(sessionId, traceId, context, e.getMessage());
        } finally {
            roundRegistry.close(traceId);
        }
    }

    /** 确认流程：确认落库 → 推 save-success → 落库助手消息（一个 data 块） */
    private void handleConfirm(String sessionId, AssistantRoundContext context, ObjectNode action) {
        ObjectNode params = JsonSupport.child(action, "params");
        String artifactId = params == null ? null : JsonSupport.text(params, "id");
        Integer version = params == null ? null : JsonSupport.integer(params, "version");
        ConfirmService.ConfirmResult result = confirmService.confirm(sessionId, artifactId, version);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", result.artifactId());
        event.put("version", String.valueOf(result.versionNo()));
        event.put("ex_id", result.themeId());
        replyWithEvent(sessionId, context, BizTypes.SAVE_SUCCESS, event);
    }

    /**
     * 前端更新 artifact（03 §3.2 的 biz-side-manage-update-material，当前用途=材料「学员可见」开关）：
     * 两种入参——材料开关 {id, version, materialId, visibility} 或通用补丁 {patches:[...]}，
     * 更新后推 biz-side-manage-artifact-updated（content 为补丁数组，前端按 path 局部更新），不进 Dify。
     * <p>
     * params 里的 id/version 是前端上下文（03 文档定义的入参），仅在前端视图与工作区不一致时打日志提示，
     * 不作为定位依据——定位始终以"会话绑定的主题 + 当前工作区产物"为准，避免前端持有过期 ID 时写错目标。
     */
    private void handleUpdateArtifact(String sessionId, AssistantRoundContext context, ObjectNode action) {
        ActAiSession session = sessionService.requireSession(sessionId);
        String themeId = session.getThemeId();
        if (themeId == null || themeId.isBlank()) {
            throw new BizException("请先创建主题再更新产物");
        }
        ObjectNode params = JsonSupport.child(action, "params");
        if (params == null) {
            throw new BizException("action 缺少 params");
        }
        warnOnStaleView(themeId, params);
        JsonNode patchesNode = params.get("patches");
        int versionNo;
        ArrayNode patches;
        if (patchesNode != null && patchesNode.isArray()) {
            // 通用补丁：与 Dify 工具 update_artifact 同构
            patches = (ArrayNode) patchesNode;
            if (patches.isEmpty()) {
                throw new BizException("patches 不能为空");
            }
            versionNo = artifactService.applyFullReplacePatches(themeId, patches);
        } else {
            // 材料「学员可见」开关（03 文档定义的入参写法）
            // 注意：不能用 params.id 兜底——按 03 文档那是 artifactId，不是材料 ID
            String materialId = firstNonBlank(JsonSupport.text(params, "materialId"),
                    JsonSupport.text(params, "fileId"));
            ArtifactService.MaterialVisibilityResult result = artifactService.updateMaterialVisibility(
                    themeId, materialId, parseVisibility(params));
            versionNo = result.versionNo();
            patches = result.patchContent();
        }
        ActAiArtifact working = artifactService.findWorking(themeId);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("id", working == null ? null : working.getId());
        event.put("version", String.valueOf(versionNo));
        event.put("content", JsonSupport.plainNode(patches));
        replyWithEvent(sessionId, context, BizTypes.ARTIFACT_UPDATED, event);
    }

    /** 前端传来的 id/version 与当前工作区不一致时仅告警（不作为定位依据，避免过期视图写错目标） */
    private void warnOnStaleView(String themeId, ObjectNode params) {
        ActAiArtifact working = artifactService.findWorking(themeId);
        if (working == null) {
            return;
        }
        String artifactId = JsonSupport.text(params, "id");
        Integer version = JsonSupport.integer(params, "version");
        boolean staleId = artifactId != null && !artifactId.isBlank() && !artifactId.equals(working.getId());
        boolean staleVersion = version != null && !version.equals(working.getVersionNo());
        if (staleId || staleVersion) {
            log.warn("前端产物视图可能过期（以工作区为准）：themeId={} 前端 id/version={}/{} 工作区={}/{}",
                    themeId, artifactId, version, working.getId(), working.getVersionNo());
        }
    }

    /**
     * 用一次助手消息回执一个业务动作（确认/材料可见性等）：
     * 开助手消息 → 推一个 bizType data part → 收尾 → 助手消息整体落库（含该 data 块，历史可重放）。
     */
    private void replyWithEvent(String sessionId, AssistantRoundContext context,
                                String bizType, Map<String, Object> params) {
        String dbId = sessionService.newDbId();
        String messageId = context.startAssistantMessage(AssistantIdentity.AGENT_ID, dbId,
                WsFrameEncoder.nowText(), null, null);
        context.pushBizEvent(bizType, params);
        context.finish();
        sessionService.appendAssistantMessage(sessionId, messageId, dbId, context.contentJson(),
                context.traceId(), null, null);
    }

    /** 解析 visibility：数字 0/1 或布尔 true/false（前端开关可能传布尔） */
    private Integer parseVisibility(ObjectNode params) {
        JsonNode node = params.get("visibility");
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? 1 : 0;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        String text = node.asText();
        if ("true".equalsIgnoreCase(text)) {
            return 1;
        }
        if ("false".equalsIgnoreCase(text)) {
            return 0;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new BizException("visibility 仅支持 0（内部可见）或 1（学员可见）");
        }
    }

    /** 取第一个非空白字符串 */
    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    /**
     * 编排智能体对话：拼上下文 → 流式调用 → 80ms 节流推 text part → 落库助手消息。
     * common-session-start 与普通聊天走同一路径，仅 query 文本不同（握手帧无正文）。
     */
    private void handleAgentChat(String sessionId, String traceId, AssistantRoundContext context,
                                 ArrayNode content, String actionName) {
        ActAiSession session = sessionService.requireSession(sessionId);
        Map<String, String> inputs = new LinkedHashMap<>(contextService.buildInputs(session));
        // 工具回调的会话定位依赖这两个值：Chatflow/工作流里都没有 sys.trace_id（实测变量列表里只有
        // sys.conversation_id / sys.workflow_run_id 等），因此由 Java 显式作为输入注入，
        // 编排应用侧只需建两个开始节点变量（session_id / trace_id）并把工具的 traceId/sessionId 参数绑上去。
        inputs.put("session_id", sessionId);
        inputs.put("trace_id", traceId == null ? "" : traceId);
        String query = buildQuery(content, actionName, isResumedSession(session));

        String dbId = sessionService.newDbId();
        String messageId = context.startAssistantMessage(AssistantIdentity.AGENT_ID, dbId,
                WsFrameEncoder.nowText(), null, null);

        DifyEventTranslator translator = new DifyEventTranslator(context::addText);
        DifyAgentClient.AgentStreamResult result = agentClient.stream(sessionId, traceId,
                session.getDifyConversationId(), inputs, query,
                new DifyAgentClient.AgentStreamListener() {
                    @Override
                    public void onChunk(String text) {
                        translator.onChunk(text);
                    }

                    @Override
                    public void onConversationId(String conversationId) {
                        // 首响即回存，保证下一轮多轮上下文可用
                        sessionService.saveDifyConversationId(sessionId, conversationId);
                    }
                });
        translator.flush();
        if (result.failed()) {
            context.addText("抱歉，本次回复未能完成：" + result.errorMessage() + "。请重试或换个说法。");
        }
        context.finish();
        if (context.blockCount() > 0) {
            sessionService.appendAssistantMessage(sessionId, messageId, dbId, context.contentJson(),
                    traceId, result.taskId(), null);
        } else {
            log.warn("本轮助手消息无内容，跳过落库 sessionId={} traceId={}", sessionId, traceId);
        }
    }

    /** 异常兜底：推一条失败提示并正常收尾，避免前端一直处于输入禁用状态 */
    private void safeFailAndFinish(String sessionId, String traceId,
                                   AssistantRoundContext context, String error) {
        try {
            if (!context.messageOpened()) {
                String dbId = sessionService.newDbId();
                context.startAssistantMessage(AssistantIdentity.AGENT_ID, dbId,
                        WsFrameEncoder.nowText(), null, null);
            }
            // 原因里已带"请重试"（如超时文案）时不再叠加兜底提示，否则输出"…请重试。请重试。"
            String reason = error == null ? "未知错误" : error;
            context.addText("抱歉，本次回复出现异常：" + reason
                    + (reason.contains("重试") ? "" : "。请重试") + "。");
            context.finish();
            if (context.blockCount() > 0) {
                sessionService.appendAssistantMessage(sessionId, context.assistantMessageId(),
                        context.agentMessageDbId(), context.contentJson(), traceId, null, null);
            }
        } catch (Exception e) {
            log.error("收尾失败 sessionId={} traceId={}", sessionId, traceId, e);
            if (!context.isOpen()) {
                return;
            }
            context.finish();
        }
    }

    /**
     * 是否为"回到已有主题的会话"：已绑定主题或已有 Dify 会话即视为非首次进入。
     * 用于给 action 帧（common-session-start）选择合适的 kickoff 文案。
     */
    private boolean isResumedSession(ActAiSession session) {
        boolean hasTheme = session.getThemeId() != null && !session.getThemeId().isBlank();
        boolean hasDifyConversation = session.getDifyConversationId() != null
                && !session.getDifyConversationId().isBlank();
        return hasTheme || hasDifyConversation;
    }

    /** query 组装：文本块拼接；仅文件块时给出材料提示；握手 action 按"新会话/回到已有会话"给不同 kickoff 文本 */
    private String buildQuery(ArrayNode content, String actionName, boolean resumedSession) {
        if (AssistantActions.SESSION_START.equals(actionName)) {
            return resumedSession ? SESSION_START_QUERY_RESUMED : SESSION_START_QUERY_NEW;
        }
        List<String> texts = new ArrayList<>();
        List<String> fileNames = new ArrayList<>();
        for (JsonNode item : content) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode block = (ObjectNode) item;
            String type = JsonSupport.text(block, "type");
            if ("text".equals(type)) {
                String text = JsonSupport.text(block, "text");
                if (text != null && !text.isBlank()) {
                    texts.add(text.trim());
                }
            } else if ("file".equals(type)) {
                String name = JsonSupport.text(JsonSupport.child(block, "extendedData"), "name");
                fileNames.add(name == null ? "未命名文件" : name);
            }
        }
        if (!texts.isEmpty()) {
            return String.join("\n", texts);
        }
        if (!fileNames.isEmpty()) {
            return "我上传了材料：" + String.join("、", fileNames) + FILE_ONLY_QUERY_SUFFIX;
        }
        return FALLBACK_QUERY;
    }

    /** 取内容块里第一个 data(action) 块（action 帧的唯一载体） */
    private ObjectNode firstActionBlock(ArrayNode content) {
        for (JsonNode item : content) {
            if (item == null || !item.isObject()) {
                continue;
            }
            ObjectNode block = (ObjectNode) item;
            if (!"data".equals(JsonSupport.text(block, "type"))) {
                continue;
            }
            ObjectNode data = JsonSupport.child(block, "data");
            if (data != null && "action".equals(JsonSupport.text(data, "type"))) {
                return data;
            }
        }
        return null;
    }
}