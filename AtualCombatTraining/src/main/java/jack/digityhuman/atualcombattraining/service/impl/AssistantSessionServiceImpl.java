package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.dto.resp.AssistantSessionResp;
import jack.digityhuman.atualcombattraining.dto.resp.MessagePageResp;
import jack.digityhuman.atualcombattraining.entity.ActAiArtifact;
import jack.digityhuman.atualcombattraining.entity.ActAiMessage;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAiMessageMapper;
import jack.digityhuman.atualcombattraining.mapper.ActAiSessionMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static jack.digityhuman.atualcombattraining.common.AssistantIdentity.AGENT_ID;
import static jack.digityhuman.atualcombattraining.common.AssistantIdentity.AGENT_NAME;
import static jack.digityhuman.atualcombattraining.common.AssistantIdentity.USER_ID;
import static jack.digityhuman.atualcombattraining.common.AssistantIdentity.USER_NAME;

/**
 * AI 会话与消息服务实现（FP-31）
 * <p>
 * cursor 编码为 {@code <created_at>|<db_id>}（created_at 固定 yyyy-MM-dd HH:mm:ss，DATETIME 秒精度可精确比较），
 * 取更早一页时按 (created_at, db_id) 严格小于游标倒序取 limit+1 条，再反转为正序返回。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantSessionServiceImpl implements AssistantSessionService {

    /** 默认分页条数（照线上 limit=1000） */
    private static final int DEFAULT_LIMIT = 1000;
    /** 单页条数上限（防全表拉取） */
    private static final int MAX_LIMIT = 2000;
    /** 会话创建者（内网固定管理员） */
    private static final String CREATOR = USER_NAME;

    private final ActAiSessionMapper sessionMapper;
    private final ActAiMessageMapper messageMapper;
    private final ActThemeMapper themeMapper;
    private final ArtifactService artifactService;

    /**
     * 建立/查找会话：带 themeId → 按主题复用（无则新建并绑定）；不带 themeId → 新建游离会话。
     * artifact 取工作区版本（无工作区取最新 confirmed），无产物返回 null。
     */
    @Override
    public AssistantSessionResp createOrFind(String themeId) {
        ActAiSession session;
        boolean isNew;
        if (themeId == null || themeId.isBlank()) {
            session = createSession(null, null);
            isNew = true;
        } else {
            ActAiSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<ActAiSession>()
                    .eq(ActAiSession::getThemeId, themeId)
                    .orderByDesc(ActAiSession::getCreatedAt)
                    .last("LIMIT 1"));
            if (existing != null) {
                session = existing;
                isNew = false;
            } else {
                ActTheme theme = themeMapper.selectById(themeId);
                session = createSession(themeId, theme == null ? null : theme.getThemeName());
                isNew = true;
            }
        }

        AssistantSessionResp resp = new AssistantSessionResp();
        resp.setSessionId(session.getId());
        resp.setThemeId(session.getThemeId());
        resp.setIsNew(isNew);
        resp.setArtifact(toArtifactResp(session.getThemeId()));
        return resp;
    }

    /** 历史消息 cursor 分页（时间正序返回） */
    @Override
    public MessagePageResp listMessages(String sessionId, Integer limit, String cursor) {
        requireSession(sessionId);
        int pageSize = normalizeLimit(limit);
        Cursor decoded = decodeCursor(cursor);

        LambdaQueryWrapper<ActAiMessage> wrapper = new LambdaQueryWrapper<ActAiMessage>()
                .eq(ActAiMessage::getSessionId, sessionId);
        if (decoded != null) {
            // (created_at, db_id) 字典序严格小于游标 → 更早的消息
            wrapper.and(w -> w
                    .lt(ActAiMessage::getCreatedAt, decoded.createdAt())
                    .or(inner -> inner
                            .eq(ActAiMessage::getCreatedAt, decoded.createdAt())
                            .lt(ActAiMessage::getDbId, decoded.dbId())));
        }
        wrapper.orderByDesc(ActAiMessage::getCreatedAt)
                .orderByDesc(ActAiMessage::getDbId)
                .last("LIMIT " + (pageSize + 1));
        List<ActAiMessage> rows = messageMapper.selectList(wrapper);

        boolean hasNextPage = rows.size() > pageSize;
        List<ActAiMessage> page = hasNextPage ? new ArrayList<>(rows.subList(0, pageSize)) : new ArrayList<>(rows);
        // 查询按时间倒序取最新一页，返回前翻转为正序（前端按顺序追加渲染）
        Collections.reverse(page);

        MessagePageResp resp = new MessagePageResp();
        List<MessagePageResp.Message> messages = new ArrayList<>();
        for (ActAiMessage row : page) {
            messages.add(toMessageResp(row));
        }
        resp.setMessages(messages);

        MessagePageResp.Pagination pagination = new MessagePageResp.Pagination();
        pagination.setLimit(pageSize);
        pagination.setHasNextPage(hasNextPage);
        pagination.setNextCursor(hasNextPage && !page.isEmpty() ? encodeCursor(page.get(0)) : "");
        resp.setPagination(pagination);
        return resp;
    }

    /** 拉取当前 artifact（右面板兜底刷新） */
    @Override
    public AssistantSessionResp.Artifact artifactOf(String sessionId) {
        ActAiSession session = requireSession(sessionId);
        return toArtifactResp(session.getThemeId());
    }

    @Override
    public ActAiSession requireSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException("会话不存在");
        }
        ActAiSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new BizException("会话不存在");
        }
        return session;
    }

    /** 消息行 ID：32 位无横线 uuid（DDL 注释即 server row uuid） */
    @Override
    public String newDbId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** 用户消息落库（dbId 预生成后显式写入，保证 ack 能立即回执） */
    @Override
    public ActAiMessage appendUserMessage(String sessionId, String messageId, String contentJson, String traceId) {
        ActAiMessage message = new ActAiMessage();
        message.setId(messageId == null || messageId.isBlank() ? newDbId() : messageId);
        message.setDbId(newDbId());
        message.setSessionId(sessionId);
        message.setRole("user");
        message.setContent(contentJson == null || contentJson.isBlank() ? "[]" : contentJson);
        message.setTraceId(traceId);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    /** 助手消息落库（轮次结束时写入完整块数组；dbId 由调用方预生成以便 message-start 先发） */
    @Override
    public ActAiMessage appendAssistantMessage(String sessionId, String messageId, String dbId,
                                              String contentJson, String traceId,
                                              String taskId, String contextId) {
        ActAiMessage message = new ActAiMessage();
        message.setId(messageId);
        message.setDbId(dbId == null || dbId.isBlank() ? newDbId() : dbId);
        message.setSessionId(sessionId);
        message.setRole("assistant");
        message.setContent(contentJson == null || contentJson.isBlank() ? "[]" : contentJson);
        message.setTraceId(traceId);
        message.setTaskId(taskId);
        message.setContextId(contextId);
        message.setCreatedAt(LocalDateTime.now());
        messageMapper.insert(message);
        return message;
    }

    /** 回填会话主题（create_topic） */
    @Override
    public void bindTheme(String sessionId, String themeId) {
        ActAiSession session = requireSession(sessionId);
        ActTheme theme = themeMapper.selectById(themeId);
        ActAiSession update = new ActAiSession();
        update.setId(session.getId());
        update.setThemeId(themeId);
        update.setTitle(theme == null ? session.getTitle() : theme.getThemeName());
        update.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(update);
    }

    /** 回存 Dify 会话 ID */
    @Override
    public void saveDifyConversationId(String sessionId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        ActAiSession update = new ActAiSession();
        update.setId(sessionId);
        update.setDifyConversationId(conversationId);
        update.setUpdatedAt(LocalDateTime.now());
        sessionMapper.updateById(update);
    }

    /** 新建会话行 */
    private ActAiSession createSession(String themeId, String title) {
        LocalDateTime now = LocalDateTime.now();
        ActAiSession session = new ActAiSession();
        session.setThemeId(themeId);
        session.setTitle(title);
        session.setCreator(CREATOR);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        sessionMapper.insert(session);
        log.info("创建 AI 会话 sessionId={} themeId={}", session.getId(), themeId);
        return session;
    }

    /** 组装 artifact 响应（工作区优先；无则 null） */
    private AssistantSessionResp.Artifact toArtifactResp(String themeId) {
        if (themeId == null || themeId.isBlank()) {
            return null;
        }
        ActAiArtifact artifact = artifactService.findDisplay(themeId);
        if (artifact == null) {
            return null;
        }
        AssistantSessionResp.Artifact resp = new AssistantSessionResp.Artifact();
        resp.setArtifactId(artifact.getId());
        resp.setVersionNo(String.valueOf(artifact.getVersionNo()));
        resp.setStatus(artifact.getStatus());
        resp.setContent(JsonSupport.plain(artifact.getContent()));
        return resp;
    }

    /** 实体转消息响应（sender 按 role 还原：表无 sender 列） */
    private MessagePageResp.Message toMessageResp(ActAiMessage row) {
        MessagePageResp.Message resp = new MessagePageResp.Message();
        resp.setDbId(row.getDbId());
        resp.setId(row.getId());
        resp.setSessionId(row.getSessionId());
        resp.setRole(row.getRole());
        resp.setContent(JsonSupport.plain(row.getContent()));
        MessagePageResp.Sender sender = new MessagePageResp.Sender();
        if ("assistant".equals(row.getRole())) {
            sender.setId(AGENT_ID);
            sender.setName(AGENT_NAME);
        } else {
            sender.setId(USER_ID);
            sender.setName(USER_NAME);
        }
        sender.setAvatar("");
        resp.setSender(sender);
        resp.setCreatedAt(row.getCreatedAt());
        return resp;
    }

    /** 条数规整：null/<=0 用默认值，超上限截断 */
    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    /** cursor 编码：created_at|db_id */
    public static String encodeCursor(ActAiMessage message) {
        return message.getCreatedAt().format(CURSOR_FORMAT) + "|" + message.getDbId();
    }

    /** cursor 解码：空/非法返回 null（视为取最新一页） */
    public static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int idx = cursor.lastIndexOf('|');
        if (idx <= 0) {
            return null;
        }
        try {
            LocalDateTime createdAt = LocalDateTime.parse(cursor.substring(0, idx), CURSOR_FORMAT);
            String dbId = cursor.substring(idx + 1);
            return dbId.isBlank() ? null : new Cursor(createdAt, dbId);
        } catch (Exception e) {
            log.warn("cursor 非法，忽略并按最新一页返回: {}", cursor);
            return null;
        }
    }

    /** 分页游标（created_at + db_id） */
    public record Cursor(LocalDateTime createdAt, String dbId) {
    }

    /** 游标时间格式（与 DATETIME 秒精度一致，保证可精确比较） */
    private static final java.time.format.DateTimeFormatter CURSOR_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
}