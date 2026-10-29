package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.dto.KngAiSourceDtos;
import jack.digityhuman.onlineclassroom.service.KngAiSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 素材源接口（AI 设计助手 / AI 出题接入在线课堂，2026-10-28）
 * <p>
 * 挂在管理端课件路径下，沿用统一 JWT（本模块不做额外拦截，见 AGENTS §3.3）：
 * 拥有 {@code act:training} 或 {@code exam:manage} 的管理员可跨模块读取已发布资源，
 * 不要求额外的 {@code kng:courseware} 菜单权限。
 * <p>
 * 只读接口：不写 kng_ref，不复制源文件，不返回磁盘路径。
 */
@RestController
@RequestMapping("/api/kng/admin/courseware/ai-sources")
@RequiredArgsConstructor
public class KngAiSourceController {

    private final KngAiSourceService aiSourceService;

    /**
     * AI 素材源列表（设计助手与出题共用）。
     * <p>
     * 固定只返回已发布、已上架、文档类且有文档源文件的课件；total 为真实总数。
     */
    @PostMapping("/list")
    public ApiResponse<KngAiSourceDtos.ListResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(defaultValue = "updateTime") String orderBy,
            @RequestParam(defaultValue = "desc") String orderType,
            @RequestBody(required = false) KngAiSourceDtos.ListQuery query) {
        return ApiResponse.success(aiSourceService.list(page, pageSize, orderBy, orderType, query));
    }

    /**
     * 抽取课件正文。
     * <p>
     * purpose 决定截断上限：designAssistant=3 万字、question=2 万字；
     * 超出部分丢弃并置 truncated=true，前端据此提示"内容过多已截断"。
     */
    @PostMapping("/{coursewareId}/extract")
    public ApiResponse<KngAiSourceDtos.ExtractResult> extract(
            @PathVariable String coursewareId,
            @RequestParam(defaultValue = "designAssistant") String purpose) {
        return ApiResponse.success(aiSourceService.extract(coursewareId, purpose));
    }
}
