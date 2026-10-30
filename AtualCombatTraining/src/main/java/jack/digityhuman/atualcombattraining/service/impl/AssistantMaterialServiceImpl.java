package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jack.digityhuman.atualcombattraining.agent.KngAiSourceClient;
import jack.digityhuman.atualcombattraining.common.BizTypes;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import jack.digityhuman.atualcombattraining.dto.resp.ImportKngMaterialsResp;
import jack.digityhuman.atualcombattraining.dto.resp.MaterialBriefResp;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActAiSession;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.service.ArtifactService;
import jack.digityhuman.atualcombattraining.service.AssistantEventPublisher;
import jack.digityhuman.atualcombattraining.service.AssistantMaterialService;
import jack.digityhuman.atualcombattraining.service.preview.MaterialPreviewService;
import jack.digityhuman.atualcombattraining.service.AssistantSessionService;
import jack.digityhuman.atualcombattraining.service.DocumentTextExtractor;
import jack.digityhuman.atualcombattraining.service.MaterialTextSummarizer;
import jack.digityhuman.common.api.BizException;
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
    /** 在线课堂材料来源标记（3=在线课堂，与前端 MaterialsTab 的来源标签口径一致） */
    private static final int FILE_SOURCE_KNG = 3;
    /** 一次导入的课件上限（原平台设计助手抽屉上限 5） */
    private static final int MAX_KNG_IMPORT = 5;
    /** 在线课堂材料 file_id 前缀：与本地文件（UUID.ext）在此分界，同会话按它 upsert */
    private static final String KNG_FILE_ID_PREFIX = "kng_";
    /** 在线课堂材料在 act_ai_material 里勾选的用途（决定抽取上限 3 万字） */
    private static final String KNG_PURPOSE = "designAssistant";
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
    /** 在线课堂素材抽取客户端（不直读 kng_*，正文由在线课堂给） */
    private final KngAiSourceClient kngAiSourceClient;
    /** 材料预览（契约 11）：上传后分类 + Office 异步转 PDF；失败不影响上传/解析/发布 */
    private final MaterialPreviewService materialPreviewService;

    public AssistantMaterialServiceImpl(AssistantSessionService sessionService,
                                        ActAiMaterialMapper materialMapper,
                                        ArtifactService artifactService,
                                        DocumentTextExtractor textExtractor,
                                        AssistantEventPublisher eventPublisher,
                                        AssistantProperties properties,
                                        @Qualifier("parseExecutor") ThreadPoolTaskExecutor parseExecutor,
                                        KngAiSourceClient kngAiSourceClient,
                                        MaterialPreviewService materialPreviewService) {
        this.sessionService = sessionService;
        this.materialMapper = materialMapper;
        this.artifactService = artifactService;
        this.textExtractor = textExtractor;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.parseExecutor = parseExecutor;
        this.kngAiSourceClient = kngAiSourceClient;
        this.materialPreviewService = materialPreviewService;
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
            // 契约 11：本地上传即分类预览（PDF/TXT/MD 直读；Office 异步转 PDF）。
            // 在线课堂材料（kng_ 前缀）无本地文件，走在线课堂自身 viewer，不进预览链路。
            if (file.getFileId() == null || !file.getFileId().startsWith(KNG_FILE_ID_PREFIX)) {
                materialPreviewService.classifyAndEnqueue(row);
            }
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

    // ---------------------------------------------------------------- 在线课堂材料导入

    /**
     * 导入在线课堂培训材料（2026-10-28）。
     * <p>
     * 与本地文件解析分开走：本地是"先落盘再异步 Tika"，这里是"在线课堂同步抽取后直接落库"。
     * 之所以同步：用户点了确定就在等结果，抽完能立刻看到"解析完成"卡片和材料 tab；
     * 单条耗时由在线课堂自己控（120s 超时），逐条失败也不会拖垮其余条目。
     * <p>
     * 不复制源文件到 act-files：在线课堂是课件的唯一宿主，复制会造成双份存储与版本漂移；
     * 预览/可见性/归集都走 act_ai_material 里的 extract_text。
     */
    @Override
    public ImportKngMaterialsResp importKngMaterials(String sessionId, List<String> coursewareIds,
                                                     String authorization) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new BizException("会话不存在，请重新进入 AI 助手");
        }
        ActAiSession session = sessionService.requireSession(sessionId);
        List<String> ids = normalizeCoursewareIds(coursewareIds);

        ImportKngMaterialsResp resp = new ImportKngMaterialsResp();
        List<Map<String, Object>> fileEntries = new ArrayList<>();
        List<String> successNames = new ArrayList<>();
        for (String coursewareId : ids) {
            try {
                KngAiSourceClient.ExtractedSource source =
                        kngAiSourceClient.extract(coursewareId, KNG_PURPOSE, authorization);
                ActAiMaterial stored = persistKng(session, source);
                if (stored.getThemeId() != null) {
                    artifactService.appendOrUpdateMaterial(stored.getThemeId(), toKngArtifactMaterial(stored, source));
                }
                resp.getMaterials().add(toImportMaterial(stored, source));
                successNames.add(stored.getName());
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", stored.getFileId());
                entry.put("name", stored.getName());
                entry.put("status", 1);
                fileEntries.add(entry);
            } catch (Exception e) {
                // 单条失败不影响其余课件：把原因收集起来，前端逐条提示
                String reason = e.getMessage() == null || e.getMessage().isBlank()
                        ? "课件解析失败" : e.getMessage();
                log.warn("在线课堂材料导入失败 sessionId={} coursewareId={} 原因={}",
                        sessionId, coursewareId, reason);
                resp.getFailures().add(ImportKngMaterialsResp.Failure.of(coursewareId, reason));
            }
        }

        // 一张卡片承载本次全部导入（已确认决策：消息流要"解析完成"卡片，且培训材料 tab 同时更新）
        pushKngParseEvent(sessionId, fileEntries, successNames, resp.getFailures().size());
        if (!resp.getMaterials().isEmpty()) {
            pushKngArtifactUpdated(session);
        }
        return resp;
    }

    /** 课件 id 去重 + 上限校验（1~5 个；空列表直接拒绝，避免"点了确定什么都没发生"） */
    private List<String> normalizeCoursewareIds(List<String> coursewareIds) {
        if (coursewareIds == null || coursewareIds.isEmpty()) {
            throw new BizException("请先选择在线课堂课件");
        }
        List<String> ids = new ArrayList<>();
        for (String id : coursewareIds) {
            String trimmed = id == null ? "" : id.trim();
            if (trimmed.isEmpty() || ids.contains(trimmed)) {
                continue;
            }
            ids.add(trimmed);
        }
        if (ids.isEmpty()) {
            throw new BizException("请先选择在线课堂课件");
        }
        if (ids.size() > MAX_KNG_IMPORT) {
            throw new BizException("一次最多导入 " + MAX_KNG_IMPORT + " 个在线课堂课件");
        }
        return ids;
    }

    /**
     * 在线课堂材料落库：按 file_id=kng_&lt;coursewareId&gt; upsert。
     * <p>
     * 同一会话重复选同一门课件时更新文本与摘要，不新增行（作业要求：重复选择不产生重复材料）。
     */
    private ActAiMaterial persistKng(ActAiSession session, KngAiSourceClient.ExtractedSource source) {
        String fileId = KNG_FILE_ID_PREFIX + source.coursewareId;
        ActAiMaterial existing = materialMapper.selectOne(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getFileId, fileId)
                .eq(ActAiMaterial::getSessionId, session.getId())
                .last("LIMIT 1"));
        String summary = MaterialTextSummarizer.summarize(source.text);
        boolean insert = existing == null;
        ActAiMaterial row = insert ? new ActAiMaterial() : existing;
        if (insert) {
            row.setFileId(truncate(fileId, LEN_FILE_ID));
            row.setVisibility(DEFAULT_VISIBILITY);
            row.setCreatedAt(LocalDateTime.now());
        }
        row.setSessionId(session.getId());
        row.setThemeId(session.getThemeId());
        // 材料名用课件名（不是源文件名）：设计助手侧看到的是"课件"，与本地上传的"文件"语义不同
        row.setName(truncate(source.coursewareTitle, LEN_NAME));
        row.setFileType(truncate(source.fileType, LEN_FILE_TYPE));
        row.setFileSize(source.fileSize);
        row.setExtractText(source.text);
        row.setSummary(summary);
        if (insert) {
            materialMapper.insert(row);
        } else {
            materialMapper.updateById(row);
        }
        return row;
    }

    /** 在线课堂材料 → artifact.materials 条目（fileSource=3，sourceId=coursewareId） */
    private ObjectNode toKngArtifactMaterial(ActAiMaterial stored, KngAiSourceClient.ExtractedSource source) {
        ObjectNode material = JsonSupport.blankObject();
        JsonSupport.putText(material, "id", truncate(stored.getFileId(), LEN_FILE_ID));
        JsonSupport.putText(material, "name", truncate(stored.getName(), LEN_NAME));
        JsonSupport.putText(material, "fileType", truncate(stored.getFileType(), LEN_FILE_TYPE));
        material.put("fileSize", source.fileSize);
        JsonSupport.putText(material, "fileSizeUnit", "B");
        JsonSupport.putInt(material, "visibility", DEFAULT_VISIBILITY);
        JsonSupport.putInt(material, "fileSource", FILE_SOURCE_KNG);
        JsonSupport.putText(material, "sourceId", source.coursewareId);
        return material;
    }

    /** act_ai_material → 导入响应条目 */
    private ImportKngMaterialsResp.Material toImportMaterial(ActAiMaterial stored,
                                                             KngAiSourceClient.ExtractedSource source) {
        ImportKngMaterialsResp.Material material = new ImportKngMaterialsResp.Material();
        material.setId(stored.getId());
        material.setFileId(stored.getFileId());
        material.setName(stored.getName());
        material.setFileType(stored.getFileType());
        material.setFileSize(stored.getFileSize());
        material.setFileSource(FILE_SOURCE_KNG);
        material.setSourceId(source.coursewareId);
        material.setVisibility(stored.getVisibility());
        return material;
    }

    /**
     * 本次导入的"解析完成"卡片。
     * <p>
     * 与本地文件解析共用同一个 bizType 和两态卡片的 data 结构：一次导入只发完成态
     * （用户点确定后抽取已同步做完，不再有"解析中"中间态），前端按 files[] 逐条列出课件名与状态。
     */
    private void pushKngParseEvent(String sessionId, List<Map<String, Object>> fileEntries,
                                   List<String> successNames, int failureCount) {
        if (fileEntries.isEmpty() && failureCount == 0) {
            return;
        }
        Map<String, Object> params = new LinkedHashMap<>();
        String contextId = UUID.randomUUID().toString().replace("-", "");
        params.put("context_id", contextId);
        params.put("call_id", contextId);
        StringBuilder content = new StringBuilder();
        if (successNames.isEmpty()) {
            content.append("在线课堂材料导入失败（").append(failureCount).append(" 个课件未能解析）");
        } else {
            content.append("已从在线课堂导入 ").append(successNames.size()).append(" 份培训材料");
            if (failureCount > 0) {
                content.append("，另有 ").append(failureCount).append(" 个课件解析失败");
            }
            content.append("。");
        }
        params.put("content", content.toString());
        // 失败项的 fileId 也列出，保证用户可以在卡片里看到"哪一门没进来"
        params.put("files", fileEntries);
        if (!successNames.isEmpty()) {
            params.put("summary", MaterialTextSummarizer.preview(String.join("、", successNames), EVENT_PREVIEW_CHARS));
        }
        eventPublisher.push(sessionId, null, BizTypes.PARSE_FILE, params);
    }

    /**
     * 通知右面板材料变化。
     * <p>
     * 推 full_replace 补丁的 /materials 段（与 Dify update_artifact / 可见性开关同一形状），
     * 前端 applyPatch 据此原地刷新"培训材料"tab；无主题时没有 artifact 可写，前端靠解析卡与
     * 会话材料接口兜底，不发这条事件。
     */
    private void pushKngArtifactUpdated(ActAiSession session) {
        if (session.getThemeId() == null || session.getThemeId().isBlank()) {
            return;
        }
        List<ActAiMaterial> rows = materialMapper.selectList(new LambdaQueryWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getSessionId, session.getId())
                .orderByAsc(ActAiMaterial::getCreatedAt)
                .orderByAsc(ActAiMaterial::getId));
        if (rows.isEmpty()) {
            return;
        }
        var materials = JsonSupport.blankArray();
        for (ActAiMaterial row : rows) {
            ObjectNode item = JsonSupport.blankObject();
            JsonSupport.putText(item, "id", truncate(row.getFileId(), LEN_FILE_ID));
            JsonSupport.putText(item, "name", truncate(row.getName(), LEN_NAME));
            JsonSupport.putText(item, "fileType", truncate(row.getFileType(), LEN_FILE_TYPE));
            if (row.getFileSize() != null) {
                item.put("fileSize", row.getFileSize());
            } else {
                item.putNull("fileSize");
            }
            JsonSupport.putText(item, "fileSizeUnit", "B");
            JsonSupport.putInt(item, "visibility", row.getVisibility() == null ? DEFAULT_VISIBILITY : row.getVisibility());
            boolean fromKng = row.getFileId() != null && row.getFileId().startsWith(KNG_FILE_ID_PREFIX);
            JsonSupport.putInt(item, "fileSource", fromKng ? FILE_SOURCE_KNG : FILE_SOURCE_SERVER);
            if (fromKng) {
                JsonSupport.putText(item, "sourceId", row.getFileId().substring(KNG_FILE_ID_PREFIX.length()));
            }
            materials.add(item);
        }
        ObjectNode patch = JsonSupport.blankObject();
        JsonSupport.putText(patch, "op", "full_replace");
        JsonSupport.putText(patch, "path", "/materials");
        patch.set("value", materials);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", null);
        params.put("version", "");
        // content 的形状是 JSON-Patch 数组（与 update_artifact / 可见性开关一致）
        params.put("content", JsonSupport.plainNode(arrayOf(patch)));
        eventPublisher.push(session.getId(), null, BizTypes.ARTIFACT_UPDATED, params);
    }

    /** 单条补丁包成数组（事件 content 的形状是 JSON-Patch 数组） */
    private com.fasterxml.jackson.databind.node.ArrayNode arrayOf(ObjectNode patch) {
        var array = JsonSupport.blankArray();
        array.add(patch);
        return array;
    }
}
