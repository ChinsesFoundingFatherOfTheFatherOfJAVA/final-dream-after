package jack.digityhuman.atualcombattraining.service.preview.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jack.digityhuman.atualcombattraining.common.JsonSupport;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeParticipant;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeParticipantMapper;
import jack.digityhuman.atualcombattraining.service.preview.DocumentPreviewConverter;
import jack.digityhuman.atualcombattraining.service.preview.MaterialPreviewService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * 材料预览实现（契约 11）
 * <p>
 * 分类规则（§1 支持矩阵）：pdf → not_needed（浏览器直接看）；txt/md → not_needed；
 * doc/docx/ppt/pptx/pps → pending（异步转 PDF）；其他扩展 → text_only。
 * <p>
 * 并发（§5）：转换前用 UPDATE ... WHERE preview_status='pending' 抢锁，抢到的线程才执行转换；
 * 同一 material 并发提交只有一个真正转换。转换在 previewExecutor（§4.2 core=1/max=2）执行，
 * 不占用训练 runtime 与报告线程池；外层再压一个信号量兜底（队列满时任务在提交线程被拒，不影响上传）。
 */
@Slf4j
@Service
public class MaterialPreviewServiceImpl implements MaterialPreviewService {

    private static final Set<String> DIRECT_READABLE = Set.of("pdf", "txt", "md");
    private static final Set<String> OFFICE = Set.of("doc", "docx", "ppt", "pptx", "pps");
    private static final long MAX_FILE_SIZE_DEFAULT = 52_428_800L;
    /** 转换认领标记（写 preview_error 字段，status 枚举不变） */
    private static final String CONVERTING_MARKER = "__converting__";

    private final ActAiMaterialMapper materialMapper;
    private final ActContentVersionMaterialMapper snapshotMapper;
    private final ActThemeMapper themeMapper;
    private final ActThemeParticipantMapper participantMapper;
    private final List<DocumentPreviewConverter> converters;
    private final ThreadPoolTaskExecutor previewExecutor;
    private final String providerName;
    private final long timeoutMs;
    private final long maxFileSize;
    private final String fileDir;
    /** 转换并发上限兜底（previewExecutor 队列满时的第二道闸） */
    private final Semaphore conversionSlots = new Semaphore(4);

    public MaterialPreviewServiceImpl(ActAiMaterialMapper materialMapper,
                                      ActContentVersionMaterialMapper snapshotMapper,
                                      ActThemeMapper themeMapper,
                                      ActThemeParticipantMapper participantMapper,
                                      List<DocumentPreviewConverter> converters,
                                      @Qualifier("previewExecutor") ThreadPoolTaskExecutor previewExecutor,
                                      @Value("${act.material.preview.provider:none}") String providerName,
                                      @Value("${act.material.preview.timeout-ms:120000}") long timeoutMs,
                                      @Value("${act.material.preview.max-file-size:52428800}") long maxFileSize,
                                      @Value("${assistant.file-dir:./act-files/}") String fileDir) {
        this.materialMapper = materialMapper;
        this.snapshotMapper = snapshotMapper;
        this.themeMapper = themeMapper;
        this.participantMapper = participantMapper;
        this.converters = converters;
        this.previewExecutor = previewExecutor;
        this.providerName = providerName == null || providerName.isBlank() ? "none" : providerName;
        this.timeoutMs = timeoutMs;
        this.maxFileSize = maxFileSize;
        this.fileDir = fileDir;
    }

    /** 确保预览目录存在（file-dir/previews，§5：输出 PDF 必须位于该目录） */
    @PostConstruct
    public void ensurePreviewDir() {
        try {
            Files.createDirectories(previewDir());
        } catch (Exception e) {
            log.warn("预览目录创建失败（转换将在首次使用时重试）：{}", e.getMessage());
        }
    }

    @Override
    public void classifyAndEnqueue(ActAiMaterial material) {
        String extension = extensionOf(material.getFileType());
        String status;
        if (DIRECT_READABLE.contains(extension)) {
            status = STATUS_NOT_NEEDED;
        } else if (OFFICE.contains(extension) && canConvert(extension, material.getFileSize())) {
            status = STATUS_PENDING;
        } else {
            // 未配置转换器 / 格式不支持 / 超大小限制 → 文本阅读是正式产品状态（§1）
            status = STATUS_TEXT_ONLY;
        }
        ActAiMaterial update = new ActAiMaterial();
        update.setId(material.getId());
        update.setPreviewStatus(status);
        materialMapper.updateById(update);
        material.setPreviewStatus(status);
        if (STATUS_PENDING.equals(status)) {
            enqueueConversion(material.getId());
        }
        log.info("材料预览分类 materialId={} fileType={} status={}",
                material.getId(), material.getFileType(), status);
    }

    @Override
    public boolean convertNow(String materialId) {
        // DB 乐观锁（§5 同一 material 防并发重复转换）：pending 且未被标记 converting 才能认领；
        // 认领标记写 preview_error 字段（status 枚举保持契约五个值不变）
        int claimed = materialMapper.update(null, new LambdaUpdateWrapper<ActAiMaterial>()
                .eq(ActAiMaterial::getId, materialId)
                .eq(ActAiMaterial::getPreviewStatus, STATUS_PENDING)
                .and(wrapper -> wrapper.isNull(ActAiMaterial::getPreviewError)
                        .ne(ActAiMaterial::getPreviewError, CONVERTING_MARKER))
                .set(ActAiMaterial::getPreviewError, CONVERTING_MARKER));
        if (claimed == 0) {
            return false;
        }
        ActAiMaterial material = materialMapper.selectById(materialId);
        if (material == null || material.getFileId() == null) {
            return false;
        }
        DocumentPreviewConverter converter = converterFor(extensionOf(material.getFileType()));
        if (converter == null) {
            markStatus(materialId, STATUS_TEXT_ONLY, "无可用转换器");
            return false;
        }
        try {
            if (!conversionSlots.tryAcquire()) {
                // 忙时保持 pending，由下一次巡检/手动刷新重试
                return false;
            }
            Path source = resolveSource(material.getFileId());
            if (source == null || !Files.exists(source)) {
                markStatus(materialId, STATUS_FAILED, "源文件不存在");
                return false;
            }
            DocumentPreviewConverter.PreviewResult result = converter.convert(
                    source, extensionOf(material.getFileType()), previewDir(),
                    Duration.ofMillis(timeoutMs));
            if (result.success()) {
                ActAiMaterial update = new ActAiMaterial();
                update.setId(materialId);
                update.setPreviewStatus(STATUS_READY);
                update.setPreviewFileId(result.previewFile());
                update.setPreviewError(null);
                materialMapper.updateById(update);
                // §8：转换成功可更新当前发布快照（派生资源，不产生版本升级）
                syncSnapshot(material.getThemeId(), material.getId(), result.previewFile(), "pdf");
                log.info("材料预览转换成功 materialId={} 耗时={}ms provider={}",
                        materialId, result.durationMs(), converter.provider());
                return true;
            }
            markStatus(materialId, STATUS_FAILED, result.error());
            log.warn("材料预览转换失败 materialId={}：{}", materialId, result.error());
            return false;
        } catch (Exception e) {
            markStatus(materialId, STATUS_FAILED, e.getMessage());
            log.warn("材料预览转换异常 materialId={}：{}", materialId, e.getMessage());
            return false;
        } finally {
            conversionSlots.release();
        }
    }

    @Override
    public Map<String, Object> viewerContext(String userId, String materialId) {
        ActAiMaterial material = materialMapper.selectById(materialId);
        if (material == null) {
            throw new jack.digityhuman.common.api.BizException("材料不存在");
        }
        // 权限：material 所在主题的发布快照里包含它，且学员仍在名单中（§6 权限）
        ActContentVersionMaterial snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<ActContentVersionMaterial>()
                        .eq(ActContentVersionMaterial::getMaterialId, materialId)
                        .last("LIMIT 1"));
        if (snapshot == null) {
            throw new jack.digityhuman.common.api.BizException("材料不在发布快照中");
        }
        ActTheme theme = themeMapper.selectById(snapshot.getThemeId());
        if (theme == null || !Objects.equals(theme.getPublishedVersionNo(), snapshot.getContentVersionNo())) {
            throw new jack.digityhuman.common.api.BizException("材料不在当前发布快照中");
        }
        Long joined = participantMapper.selectCount(new LambdaQueryWrapper<ActThemeParticipant>()
                .eq(ActThemeParticipant::getThemeId, snapshot.getThemeId())
                .eq(ActThemeParticipant::getUserId, userId)
                .eq(ActThemeParticipant::getStatus, "active"));
        if (joined == null || joined == 0) {
            throw new jack.digityhuman.common.api.BizException("无权访问该材料");
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("id", materialId);
        context.put("name", material.getName());
        context.put("sourceType", snapshot.getSourceType());
        context.put("fileType", snapshot.getFileType());
        // 在线课堂材料走在线课堂 viewer（§6 权限注）
        if ("online_classroom".equals(snapshot.getSourceType())) {
            context.put("renderMode", "online_classroom");
            context.put("sourceId", snapshot.getSourceId());
            context.put("notice", null);
            return context;
        }
        String status = material.getPreviewStatus() == null ? STATUS_TEXT_ONLY : material.getPreviewStatus();
        context.put("previewStatus", status);
        boolean pdfReady = STATUS_READY.equals(status) && material.getPreviewFileId() != null;
        context.put("renderMode", pdfReady ? "pdf" : "text");
        context.put("viewerUrl", pdfReady
                ? "/api/act/student/materials/" + materialId + "/preview.pdf" : null);
        context.put("textUrl", "/api/act/student/materials/" + materialId + "/text");
        context.put("originalUrl", "/api/act/student/materials/" + materialId + "/download");
        // §1：文本回退是正式产品状态，界面必须明示
        context.put("notice", pdfReady ? null : "当前为文本预览，版式可能与原文件不同");
        return context;
    }

    // ------------------------------------------------------------------ helpers

    private void enqueueConversion(String materialId) {
        try {
            previewExecutor.execute(() -> convertNow(materialId));
        } catch (Exception e) {
            // 队列满/关闭：保持 pending，不阻塞上传与发布（§10）
            log.warn("预览任务提交失败（保持 pending）materialId={}：{}", materialId, e.getMessage());
        }
    }

    private void markStatus(String materialId, String status, String error) {
        ActAiMaterial update = new ActAiMaterial();
        update.setId(materialId);
        update.setPreviewStatus(status);
        update.setPreviewError(error == null ? null
                : error.length() > 500 ? error.substring(0, 500) : error);
        materialMapper.updateById(update);
    }

    /** 发布快照同步：仅更新 preview 派生字段（§8），不动内容 */
    private void syncSnapshot(String themeId, String materialId, String previewFileId, String renderMode) {
        if (themeId == null) {
            return;
        }
        snapshotMapper.update(null, new LambdaUpdateWrapper<ActContentVersionMaterial>()
                .eq(ActContentVersionMaterial::getThemeId, themeId)
                .eq(ActContentVersionMaterial::getMaterialId, materialId)
                .set(ActContentVersionMaterial::getPreviewFileId, previewFileId)
                .set(ActContentVersionMaterial::getRenderMode, renderMode));
    }

    private DocumentPreviewConverter converterFor(String extension) {
        return converters.stream()
                .filter(converter -> providerName.equals(converter.provider()))
                .filter(converter -> converter.supports(extension))
                .findFirst()
                .orElse(null);
    }

    private boolean canConvert(String extension, Long fileSize) {
        if (converterFor(extension) == null) {
            return false;
        }
        return fileSize == null || fileSize <= maxFileSize;
    }

    /** 源文件路径：file-dir 规范化后校验，防路径穿越（§5）；公开供测试复用 */
    public Path resolveSource(String fileId) {
        if (fileId == null || fileId.isBlank()) {
            return null;
        }
        Path base = Paths.get(fileDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(fileId).normalize();
        if (!resolved.startsWith(base)) {
            log.warn("材料路径越界已拦截：{}", fileId);
            return null;
        }
        return resolved;
    }

    private Path previewDir() {
        return Paths.get(fileDir).toAbsolutePath().normalize().resolve("previews");
    }

    private String extensionOf(String fileType) {
        if (fileType == null) {
            return "";
        }
        return fileType.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
