package jack.digityhuman.atualcombattraining.service.impl;

import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.service.DocumentTextExtractor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 基于 Apache Tika 的文档文本提取实现（FP-42）
 * <p>
 * 安全与稳健性处理：
 * <ul>
 *   <li>只接受配置目录（assistant.file-dir）内的文件——路径必须落在该目录下，防止越权读任意路径；</li>
 *   <li>文件大小上限由上传环节把关（assistant.file-max-size-bytes），此处再校验一次；</li>
 *   <li>提取结果按 extract 上限截断，避免超大文档把内存/数据库写爆；</li>
 *   <li>解析异常统一转业务异常（含类型名），由材料服务落成"解析失败"，不影响上传本身。</li>
 * </ul>
 * 说明：Tika 解析不可信文档的加固（XML 外部实体、压缩炸弹深度限制）建议随 Tika 版本升级持续跟进，
 * 当前依赖 Tika 2.9.2 的默认安全配置 + 白名单扩展名 + 文件大小上限。
 */
@Slf4j
@Service
public class TikaDocumentTextExtractor implements DocumentTextExtractor {

    /** 单文件提取上限（字符）：超出部分丢弃，避免极端文档撑爆存储与上下文 */
    private static final int MAX_EXTRACT_CHARS = 500_000;
    /** 文件大小上限（字节），与上传环节保持一致 */
    private static final long MAX_FILE_BYTES = 52_428_800L;

    private final Tika tika = new Tika();

    /** 材料存储目录（从配置读取，用于限定可读范围） */
    private final Path storageDir;

    public TikaDocumentTextExtractor(jack.digityhuman.atualcombattraining.config.AssistantProperties properties) {
        this.storageDir = Paths.get(properties.getFileDir()).toAbsolutePath().normalize();
    }

    @Override
    public String extract(String storedFilePath) {
        Path target = Paths.get(storedFilePath).toAbsolutePath().normalize();
        if (!target.startsWith(storageDir)) {
            throw new BizException("非法的材料路径");
        }
        if (!Files.isRegularFile(target)) {
            throw new BizException("材料文件不存在：" + target.getFileName());
        }
        try {
            long size = Files.size(target);
            if (size > MAX_FILE_BYTES) {
                throw new BizException("材料文件超过解析上限");
            }
            // 空文件：Tika 会抛 ZeroByteFileException，这里直接按"无文本"处理（上传侧也会拦空文件，此处兜底）
            if (size == 0L) {
                return "";
            }
        } catch (java.io.IOException e) {
            throw new BizException("材料文件不可读：" + e.getMessage());
        }
        try {
            String text = tika.parseToString(target);
            if (text == null) {
                return "";
            }
            String normalized = text.strip();
            if (normalized.length() > MAX_EXTRACT_CHARS) {
                log.warn("材料文本超长，已截断：{} -> {} 字符", normalized.length(), MAX_EXTRACT_CHARS);
                return normalized.substring(0, MAX_EXTRACT_CHARS);
            }
            return normalized;
        } catch (Exception e) {
            log.error("材料解析失败 file={}", target.getFileName(), e);
            throw new BizException("材料解析失败：" + e.getClass().getSimpleName());
        }
    }
}