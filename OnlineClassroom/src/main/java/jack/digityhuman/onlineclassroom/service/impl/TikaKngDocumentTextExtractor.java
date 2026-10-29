package jack.digityhuman.onlineclassroom.service.impl;

import jack.digityhuman.onlineclassroom.service.KngDocumentTextExtractor;
import jack.digityhuman.onlineclassroom.service.StorageService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * 基于 Apache Tika 的课件文档抽取实现（AI 素材源接入）
 * <p>
 * 与 AI 设计助手材料解析共用同一套解析栈（ACT 的 {@code TikaDocumentTextExtractor}，2.9.2），
 * 不在本工程另引一套 PDFBox/POI：同一份 pptx/pdf 在两个模块的抽取结果才能保持一致，
 * 也避免再维护一份格式白名单与降级逻辑（偏差记于 02-技术实现方案 §4.3）。
 * <p>
 * 安全边界：
 * <ul>
 *   <li>路径一律经 {@link StorageService#resolveStoredPath} 归一化，必须落在 {@code kng.upload.dir} 内，
 *       越界抛业务异常（连同 {@code ../} 形式的库内路径一起挡掉）；</li>
 *   <li>扩展名白名单固定六个文档格式，非白名单直接拒绝，不交给 Tika 嗅探；</li>
 *   <li>先判文件大小再解析，损坏/加密/无文本层由 Tika 抛错，统一翻译成中文业务异常；</li>
 *   <li>绝不把磁盘绝对路径返回给调用方或前端。</li>
 * </ul>
 */
@Slf4j
@Service
public class TikaKngDocumentTextExtractor implements KngDocumentTextExtractor {

    /** 允许抽取的文档扩展名（与 04-施工提示词 §必测场景的六格式一致） */
    private static final Set<String> ALLOWED_EXT = Set.of("doc", "docx", "ppt", "pptx", "pps", "pdf");
    /** 单文件硬上限（字节）：课件文档 200MB 上限的兜底，防止极端文件把解析线程钉死 */
    private static final long MAX_FILE_BYTES = 209_715_200L;
    /** 抽取字符硬上限：任何用途都不会要超过 3 万字，多出的部分没有消费者 */
    private static final int MAX_HARD_CHARS = 30_000;

    private final Tika tika = new Tika();
    private final StorageService storageService;

    public TikaKngDocumentTextExtractor(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public Extracted extract(String storagePath, int maxChars) {
        if (storagePath == null || storagePath.isBlank()) {
            throw new jack.digityhuman.common.api.BizException("课件源文件路径为空");
        }
        String extension = extensionOf(storagePath);
        if (!ALLOWED_EXT.contains(extension)) {
            throw new jack.digityhuman.common.api.BizException("暂不支持该文件类型抽取：" + extension);
        }
        int limit = Math.min(maxChars <= 0 ? MAX_HARD_CHARS : maxChars, MAX_HARD_CHARS);
        Path target = storageService.resolveStoredPath(storagePath);
        if (!Files.isRegularFile(target)) {
            throw new jack.digityhuman.common.api.BizException("课件源文件不存在，可能已被清理");
        }
        long size;
        try {
            size = Files.size(target);
        } catch (java.io.IOException e) {
            throw new jack.digityhuman.common.api.BizException("课件源文件不可读");
        }
        if (size > MAX_FILE_BYTES) {
            throw new jack.digityhuman.common.api.BizException("课件源文件超过解析上限");
        }
        if (size == 0L) {
            // 空文件交给 Tika 会抛 ZeroByteFileException，这里按"未提取到文本"处理
            throw new jack.digityhuman.common.api.BizException("课件源文件为空，未提取到文本");
        }
        String text;
        try {
            text = tika.parseToString(target);
        } catch (Exception e) {
            // 加密、损坏、扫描件无文本层等都在这里收口，错误信息不带磁盘路径
            log.error("课件文档解析失败 storagePath={} 扩展名={}", storagePath, extension, e);
            throw new jack.digityhuman.common.api.BizException("课件文件解析失败，可能是文件损坏或加密");
        }
        String normalized = text == null ? "" : text.strip();
        if (normalized.isBlank()) {
            throw new jack.digityhuman.common.api.BizException("未提取到文本内容");
        }
        if (normalized.length() > limit) {
            return new Extracted(normalized.substring(0, limit), true);
        }
        return new Extracted(normalized, false);
    }

    /** 存储路径的小写扩展名（无扩展名返回空串） */
    private String extensionOf(String storagePath) {
        String name = storagePath.toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot + 1) : "";
    }
}
