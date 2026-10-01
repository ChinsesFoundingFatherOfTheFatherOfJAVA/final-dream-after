package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import jack.digityhuman.onlineclassroom.service.CoursewareOpService;
import jack.digityhuman.onlineclassroom.service.StorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 课件上下架、移动、复制、删除、下载和占位记录接口。
 */
@RestController
@RequestMapping("/api/kng/admin/courseware")
@RequiredArgsConstructor
public class CoursewareOpController {

    private final CoursewareOpService operationService;
    private final StorageService storageService;
    private final CurrentUserService currentUserService;

    /** 下架单课件。 */
    @PostMapping("/{id}/take-down")
    public ApiResponse<KngCoursewareDtos.CountResponse> takeDown(@PathVariable String id) {
        return ApiResponse.success(operationService.takeDown(id));
    }

    /** 批量下架。 */
    @PostMapping("/batch/take-down")
    public ApiResponse<KngCoursewareDtos.CountResponse> batchTakeDown(
            @RequestBody KngCoursewareDtos.IdsRequest request) {
        return ApiResponse.success(operationService.batchTakeDown(request));
    }

    /** 批量上架。 */
    @PostMapping("/batch/put-on")
    public ApiResponse<KngCoursewareDtos.CountResponse> batchPutOn(
            @RequestBody KngCoursewareDtos.IdsRequest request) {
        return ApiResponse.success(operationService.batchPutOn(request));
    }

    /** 批量移动到目标分类。 */
    @PostMapping("/batch/move")
    public ApiResponse<KngCoursewareDtos.CountResponse> move(
            @RequestBody KngCoursewareDtos.MoveRequest request) {
        return ApiResponse.success(operationService.move(request));
    }

    /** 复制课件（标题加副本后缀并去重）。 */
    @PostMapping("/{id}/copy")
    public ApiResponse<KngCoursewareDtos.IdResponse> copy(
            @PathVariable String id,
            @RequestBody(required = false) KngCoursewareDtos.CopyRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(operationService.copy(
                id, request, currentUserService.userId(authorization)));
    }

    /** 删除单课件（未被引用时才执行，级联清理文件/附件）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<KngCoursewareDtos.CountResponse> delete(@PathVariable String id) {
        return ApiResponse.success(operationService.delete(id));
    }

    /** 批量删除。 */
    @PostMapping("/batch/delete")
    public ApiResponse<KngCoursewareDtos.CountResponse> batchDelete(
            @RequestBody KngCoursewareDtos.IdsRequest request) {
        return ApiResponse.success(operationService.batchDelete(request));
    }

    @GetMapping("/{id}/references")
    public ApiResponse<List<?>> references(@PathVariable String id) {
        return ApiResponse.success(operationService.references(id));
    }

    @GetMapping("/{id}/download-records")
    public ApiResponse<KngCoursewareDtos.DownloadRecordResponse> downloadRecords(@PathVariable String id) {
        return ApiResponse.success(operationService.downloadRecords(id));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable String id) {
        KngCoursewareFile file = operationService.downloadFile(id);
        Path path = storageService.resolveStoredPath(file.getStoragePath());
        if (!Files.isRegularFile(path)) {
            throw new BizException("课件源文件不存在");
        }
        String contentType;
        try {
            contentType = Files.probeContentType(path);
        } catch (Exception ignored) {
            contentType = null;
        }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(file.getFileName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(contentType == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(path));
    }
}
