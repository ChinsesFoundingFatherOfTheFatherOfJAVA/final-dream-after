package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import jack.digityhuman.atualcombattraining.dto.resp.MaterialBriefResp;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantMaterialService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.DocumentTextExtractor;
import jack.digityhuman.atualcombattraining.service.MaterialTextSummarizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 材料解析服务实现（FP-42）
 * <p>
 * 解析在 parseExecutor 上异步执行（上传接口立即返回 fileId，不阻塞前端）；
 * 该池与生成任务的 generateExecutor 分开——主题/案例生成单次要几百秒，
 * 共用会把秒级的解析排到十几分钟后，前端「正在解析文件…」卡片因此长期转圈。
 * 事件用"独立成轮"方式推送（上传请求不在对话轮次内），两态事件携带同一 files[].id，
 * 前端据此把"解析中"卡片原地更新为"解析完成"。
 */
@Slf4j
@Service
public class AssistantMaterialServiceImpl implements AssistantMaterialService {

    /** 解析完成卡片内联文案的摘要预览长度 */
    private static final int EVENT_PREVIEW_CHARS = 120;
    /** 材料可见性默认：1 学员可见 */
    private static final int DEFAULT_VISIBILITY = 1;
    /** 材料来源标记（2=服务端存储，与 03 §3.5 示例语义一致） */
    private static final int FILE_SOURCE_SERVER = 2;
    /** 材料名/类型列宽（DDL） */
    private static final int LEN_NAME = 200;
    private static final int LEN_FILE_TYPE = 16;
    private static final int LEN_FILE_ID = 64;

    private final AssistantSessionService sessionService;
    private final ActAiMaterialMapper materialMapper;
    private final ArtifactService artifactService;
    private final DocumentTextExtractor textExtractor;
    private final AssistantEventPublisher eventPublisher;
    private final AssistantProperties properties;
    private final ThreadPoolTaskExecutor parseExecutor;

    public AssistantMaterialServiceImpl(AssistantSessionService sessionService,
                                        ActAiMaterialMapper materialMapper,
                                        ArtifactService artifactService,
                                        DocumentTextExtractor textExtractor,
                                        AssistantEventPublisher eventPublisher,
                                        AssistantProperties properties,
                                        @Qualifier("parseExecutor") ThreadPoolTaskExecutor parseExecutor) {
        this.sessionService = sessionService;
        this.materialMapper = materialMapper;
        this.artifactService = artifactService;
        this.textExtractor = textExtractor;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.parseExecutor = parseExecutor;
    }

    /**
     * 受理解析：先推"解析中"（同一个 context_id/call_id 贯穿两态），再异步提取。
     */
    @Override
    public void acceptParse(String sessionId, FileUploadResp file) {
        String contextId = UUID.randomUUID().toString().replace("-", "");
        String callId = UUID.randomUUID().toString().replace("-", "");
        pushParseEvent(sessionId, file, contextId, callId, 0,
                "正在解析材料《" + file.getName() + "》……", null);
        parseExecutor.execute(() -> runParse(sessionId, file, contextId, callId));
    }

    /** 异步解析主体：提取 → 摘要 → 落库/入 artifact → 推"解析完成/失败" */
    private void runParse(String sessionId, FileUploadResp file, String contextId, String callId) {
        try {
            String text = textExtractor.extract(resolveStoredPath(file.getFileId()).toString());
            String summary = MaterialTextSummarizer.summarize(text);
            ActAiMaterial stored = persist(sessionId, file, text, summary);
            if (stored.getThemeId() != null) {
                artifactService.appendOrUpdateMaterial(stored.getThemeId(), toArtifactMaterial(file));
            }
            String content = buildSuccessContent(file, summary, text);
            pushParseEvent(sessionId, file, contextId, callId, 1, content,
                    MaterialTextSummarizer.preview(summary, EVENT_PREVIEW_CHARS));
            log.info("材料解析完成 fileId={} 提取长度={} 摘要长度={} 是否截断={}",
                    file.getFileId(), text.length(), summary.length(), MaterialTextSummarizer.truncated(text));
        } catch (Exception e) {
            // 失败不新增 status 枚举：仍回 status=1（完成态），在 content 内说明失败原因
            log.error("材料解析失败 fileId={}", file.getFileId(), e);
            pushParseEvent(sessionId, file, contextId, callId, 1,
                    "材料《" + file.getName() + "》解析失败：" + e.getMessage() + "（可重新上传或改用 txt/md）", null);
        }
    }

    /** 解析结果落库：按 file_id upsert（同一文件重复解析只更新文本与摘要） */
    private ActAiMaterial persist(String sessionId, FileUploadResp file, String text, String summary) {
        ActAiSession session = sessionService.requireSession(sessionId);
        ActAiMaterial existing = materialMapper.selectOne(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getFileId, file.getFileId())
                .last("LIMIT 1"));
        boolean insert = existing == null;
        ActAiMaterial row = insert ? new ActAiMaterial() : existing;
        if (insert) {
            row.setFileId(file.getFileId());
            row.setVisibility(DEFAULT_VISIBILITY);
            row.setCreatedAt(LocalDateTime.now());
        }
        row.setSessionId(sessionId);
        row.setThemeId(session.getThemeId());
        row.setName(truncate(file.getName(), LEN_NAME));
        row.setFileType(truncate(file.getFileType(), LEN_FILE_TYPE));
        row.setFileSize(file.getFileSize());
        row.setExtractText(text);
        row.setSummary(summary);
        if (insert) {
            materialMapper.insert(row);
        } else {
            materialMapper.updateById(row);
        }
        return row;
    }

    /** 建主题后归集：回填 theme_id 并把材料追加进工作区 artifact.materials */
    @Override
    public void linkSessionMaterials(String sessionId, String themeId) {
        List<ActAiMaterial> pending = materialMapper.selectList(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getSessionId, sessionId)
                .isNull(ActAiMaterial::getThemeId));
        if (pending.isEmpty()) {
            return;
        }
        for (ActAiMaterial material : pending) {
            ActAiMaterial update = new ActAiMaterial();
            update.setId(material.getId());
            update.setThemeId(themeId);
            materialMapper.updateById(update);
            FileUploadResp file = new FileUploadResp();
            file.setFileId(material.getFileId());
            file.setName(material.getName());
            file.setFileType(material.getFileType());
            file.setFileSize(material.getFileSize());
            artifactService.appendOrUpdateMaterial(themeId, toArtifactMaterial(file));
        }
        log.info("新建主题后归集会话材料 sessionId={} themeId={} 数量={}",
                sessionId, themeId, pending.size());
    }

    /** 会话材料解析结果（"文件解析完成"卡片展开时按需拉取；无材料返回空列表） */
    @Override
    public List<MaterialBriefResp> listSessionMaterials(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        List<ActAiMaterial> rows = materialMapper.selectList(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getSessionId, sessionId)
                .orderByAsc(ActAiMaterial::getCreatedAt)
                .orderByAsc(ActAiMaterial::getId));
        List<MaterialBriefResp> result = new ArrayList<>(rows.size());
        for (ActAiMaterial row : rows) {
            result.add(MaterialBriefResp.of(row.getId(), row.getFileId(), row.getName(), row.getFileType(),
                    row.getFileSize(), row.getVisibility(), row.getExtractText()));
        }
        return result;
    }

    /** 材料 → artifact.materials 条目（字段见 03 §3.5；fileSize 用字节并标单位 B，保留精度） */
    private ObjectNode toArtifactMaterial(FileUploadResp file) {
        ObjectNode material = JsonSupport.blankObject();
        JsonSupport.putText(material, "id", truncate(file.getFileId(), LEN_FILE_ID));
        JsonSupport.putText(material, "name", truncate(file.getName(), LEN_NAME));
        JsonSupport.putText(material, "fileType", truncate(file.getFileType(), LEN_FILE_TYPE));
        if (file.getFileSize() != null) {
            material.put("fileSize", file.getFileSize());
        } else {
            material.putNull("fileSize");
        }
        JsonSupport.putText(material, "fileSizeUnit", "B");
        JsonSupport.putInt(material, "visibility", DEFAULT_VISIBILITY);
        JsonSupport.putInt(material, "fileSource", FILE_SOURCE_SERVER);
        return material;
    }

    /** 推 parse-file 事件（独立成轮） */
    private void pushParseEvent(String sessionId, FileUploadResp file, String contextId, String callId,
                               int status, String content, String summaryPreview) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("context_id", contextId);
        params.put("call_id", callId);
        params.put("content", content);
        Map<String, Object> fileEntry = new LinkedHashMap<>();
        fileEntry.put("id", file.getFileId());
        fileEntry.put("name", file.getName());
        fileEntry.put("status", status);
        params.put("files", List.of(fileEntry));
        if (summaryPreview != null && !summaryPreview.isBlank()) {
            params.put("summary", summaryPreview);
        }
        eventPublisher.push(sessionId, null, BizTypes.PARSE_FILE, params);
    }

    /** 解析完成文案：文件名 + 文本量 + 摘要预览（前端卡片内联展示） */
    private String buildSuccessContent(FileUploadResp file, String summary, String text) {
        StringBuilder text2 = new StringBuilder();
        text2.append("材料《").append(file.getName()).append("》解析完成");
        text2.append("（提取 ").append(text.length()).append(" 字");
        if (MaterialTextSummarizer.truncated(text)) {
            text2.append("，摘要已按首尾截断");
        }
        text2.append("）");
        String preview = MaterialTextSummarizer.preview(summary, EVENT_PREVIEW_CHARS);
        if (!preview.isBlank()) {
            text2.append("：").append(preview);
        }
        return text2.toString();
    }

    /** 落盘路径（act-files/ 下，文件名由服务端生成） */
    private Path resolveStoredPath(String fileId) {
        return Paths.get(properties.getFileDir()).toAbsolutePath().normalize().resolve(fileId);
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}