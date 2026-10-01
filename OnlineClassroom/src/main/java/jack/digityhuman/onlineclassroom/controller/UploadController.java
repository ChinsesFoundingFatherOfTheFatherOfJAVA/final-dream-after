package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.service.UploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Web 分片上传协议。
 */
@RestController
@RequestMapping("/api/kng/admin/upload")
@RequiredArgsConstructor
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/init")
    public ApiResponse<UploadService.InitResult> init(@RequestBody UploadService.InitRequest request) {
        return ApiResponse.success(uploadService.init(request));
    }

    @PostMapping("/chunk")
    public ApiResponse<Void> chunk(
            @RequestParam String uploadId,
            @RequestParam int chunkIndex,
            @RequestParam("file") MultipartFile file) {
        uploadService.uploadChunk(uploadId, chunkIndex, file);
        return ApiResponse.success(null);
    }

    @PostMapping("/merge")
    public ApiResponse<UploadService.MergeResult> merge(@RequestBody UploadService.MergeRequest request) {
        return ApiResponse.success(uploadService.merge(request));
    }
}
