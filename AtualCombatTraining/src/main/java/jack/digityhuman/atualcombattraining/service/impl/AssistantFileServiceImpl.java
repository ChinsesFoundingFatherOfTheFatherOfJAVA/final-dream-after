package jack.digityhuman.atualcombattraining.service.impl;

import jack.digityhuman.common.api.BizException;
import jack.digityhuman.atualcombattraining.config.AssistantProperties;
import jack.digityhuman.atualcombattraining.dto.resp.FileUploadResp;
import jack.digityhuman.atualcombattraining.service.AssistantFileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 材料文件服务实现（FP-42）
 * <p>
 * 存储规则：文件名 = 服务端 uuid + 原名扩展名，落盘到 assistant.file-dir；
 * 客户端原始文件名仅用于展示与类型判定，不参与路径拼接。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantFileServiceImpl implements AssistantFileService {

    /** 允许的材料类型白名单（03 文档枚举） */
    private static final Set<String> ALLOWED_TYPES = Set.of("ppt", "pptx", "doc", "docx", "pdf", "txt", "md");

    private final AssistantProperties properties;

    @Override
    public FileUploadResp store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException("上传文件为空");
        }
        long maxSize = properties.getFileMaxSizeBytes();
        if (file.getSize() > maxSize) {
            throw new BizException("文件超过大小上限（" + (maxSize / 1024 / 1024) + "MB）");
        }
        String originalName = file.getOriginalFilename();
        String fileType = resolveType(originalName);
        if (!ALLOWED_TYPES.contains(fileType)) {
            throw new BizException("不支持的文件类型");
        }
        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + fileType;
        Path dir = Paths.get(properties.getFileDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
            Path target = dir.resolve(storedName).normalize();
            if (!target.startsWith(dir)) {
                // 双保险：目标路径必须仍在存储目录内
                throw new BizException("非法的存储路径");
            }
            file.transferTo(target);
        } catch (IOException e) {
            log.error("材料文件落盘失败 name={}", originalName, e);
            throw new BizException("文件保存失败，请重试");
        }
        FileUploadResp resp = new FileUploadResp();
        resp.setFileId(storedName);
        resp.setName(originalName == null ? storedName : originalName);
        resp.setFileType(fileType);
        resp.setFileSize(file.getSize());
        log.info("材料上传成功 fileId={} name={} size={}", storedName, originalName, file.getSize());
        return resp;
    }

    /** 取扩展名并小写（无扩展名返回空串） */
    private String resolveType(String originalName) {
        if (originalName == null) {
            return "";
        }
        int idx = originalName.lastIndexOf('.');
        if (idx < 0 || idx == originalName.length() - 1) {
            return "";
        }
        return originalName.substring(idx + 1).toLowerCase(Locale.ROOT);
    }
}