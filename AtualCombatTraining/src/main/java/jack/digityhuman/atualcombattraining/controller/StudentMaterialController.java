package jack.digityhuman.atualcombattraining.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.atualcombattraining.common.CurrentUserService;
import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;
import jack.digityhuman.atualcombattraining.entity.ActContentVersionMaterial;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.mapper.ActAiMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActContentVersionMaterialMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.service.preview.MaterialPreviewService;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Objects;

/**
 * 学员端材料查看接口（契约 11 §6，API-F02a~F02c + viewer-context）
 * <p>
 * 鉴权统一在 {@link MaterialPreviewService#viewerContext} 的同一套规则上：
 * 参与关系 + 材料属于当前发布快照。preview_error 等底层细节不返回给学员（§2）。
 * <p>
 * Range 说明（偏差）：首期直接整文件返回（内网、材料 ≤50MB）；浏览器 PDF 查看器
 * 不依赖 Range 也能工作，Range 分片按 AC 验收结果再补。
 */
@Slf4j
@RestController
@RequestMapping("/api/act/student/materials")
@RequiredArgsConstructor
public class StudentMaterialController {

    private final MaterialPreviewService previewService;
    private final ActAiMaterialMapper materialMapper;
    private final ActContentVersionMaterialMapper snapshotMapper;
    private final ActThemeMapper themeMapper;
    private final CurrentUserService currentUserService;

    /** 查看上下文：renderMode/viewerUrl/textUrl/originalUrl/notice（§6.1） */
    @GetMapping("/{materialId}/viewer-context")
    public ApiResponse<Map<String, Object>> viewerContext(
            @PathVariable String materialId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(previewService.viewerContext(
                requireUser(authorization), materialId));
    }

    /** API-F04 预览 PDF（转换产物） */
    @GetMapping("/{materialId}/preview.pdf")
    public ResponseEntity<FileSystemResource> previewPdf(
            @PathVariable String materialId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ActAiMaterial material = requireAccessible(requireUser(authorization), materialId);
        if (material.getPreviewFileId() == null) {
            throw new BizException("版式预览不可用");
        }
        Path pdf = previewFile(material.getPreviewFileId());
        if (!Files.exists(pdf)) {
            throw new BizException("版式预览不可用");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new FileSystemResource(pdf));
    }

    /** API-F05 提取文本（回退阅读；按发布快照鉴权） */
    @GetMapping(value = "/{materialId}/text", produces = MediaType.TEXT_PLAIN_VALUE + ";charset=utf-8")
    public ResponseEntity<String> text(
            @PathVariable String materialId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ActAiMaterial material = requireAccessible(requireUser(authorization), materialId);
        String text = material.getExtractText() == null ? "" : material.getExtractText();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/plain;charset=utf-8"))
                .body(text);
    }

    /** API-F02c 下载原文件 */
    @GetMapping("/{materialId}/download")
    public ResponseEntity<FileSystemResource> download(
            @PathVariable String materialId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        ActAiMaterial material = requireAccessible(requireUser(authorization), materialId);
        Path source = sourceFile(material);
        if (source == null || !Files.exists(source)) {
            throw new BizException("原文件不存在");
        }
        String encodedName = URLEncoder.encode(
                material.getName() == null ? "material" : material.getName(),
                StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .body(new FileSystemResource(source));
    }

    // ------------------------------------------------------------------ helpers

    /** 共用访问校验：参与关系 + 材料属于当前发布快照（§6 权限） */
    private ActAiMaterial requireAccessible(String userId, String materialId) {
        // viewerContext 内部做同样的快照 + 参与关系校验，这里复用它拒绝越权
        previewService.viewerContext(userId, materialId);
        return materialMapper.selectById(materialId);
    }

    private Path previewFile(String previewFileId) {
        Path base = Paths.get("./act-files/").toAbsolutePath().normalize().resolve("previews");
        Path resolved = base.resolve(previewFileId).normalize();
        // 防路径穿越（§5）
        return resolved.startsWith(base) ? resolved : null;
    }

    private Path sourceFile(ActAiMaterial material) {
        Path base = Paths.get("./act-files/").toAbsolutePath().normalize();
        Path resolved = base.resolve(material.getFileId()).normalize();
        return resolved.startsWith(base) ? resolved : null;
    }

    private String requireUser(String authorization) {
        String userId = currentUserService.userId(authorization);
        if ("0".equals(userId)) {
            throw new BizException("未获取到登录用户，请重新登录");
        }
        return userId;
    }
}
