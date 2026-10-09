package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.speechtraining.dto.req.AiGenDtos;
import jack.digityhuman.speechtraining.dto.resp.AiGenRespDtos;
import jack.digityhuman.speechtraining.service.AIGenService;
import jack.digityhuman.speechtraining.service.KngMaterialService;
import jack.digityhuman.speechtraining.service.MaterialExtractService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * AI生成话术闭环三步接口（v2.0 03 §1：/admin/ai/gen-scene|gen-outline|gen-script-v2|
 * import-script|material/extract|material/kng-list|material/kng-extract）。
 */
@RestController
@RequestMapping("/api/admin/ai")
@RequiredArgsConstructor
public class AIGenController {

    private final AIGenService aiGenService;
    private final MaterialExtractService materialExtractService;
    private final KngMaterialService kngMaterialService;

    /** SG-03a 本地素材抽取（multipart，Tika 口径的 POI/PDFBox 抽取）。 */
    @PostMapping("/material/extract")
    public ApiResponse<AiGenRespDtos.MaterialExtractResp> extractMaterial(
            @RequestParam("file") MultipartFile file) {
        MaterialExtractService.ExtractResult result = materialExtractService.extract(file);
        AiGenRespDtos.MaterialExtractResp resp = new AiGenRespDtos.MaterialExtractResp();
        resp.setFileName(file.getOriginalFilename());
        resp.setText(result.text());
        resp.setTruncated(result.truncated());
        resp.setChars(result.chars());
        return ApiResponse.success(resp);
    }

    /** SG-03b 在线课堂文档类课件列表（选课件抽屉数据源，仅名称关键词搜索的极简口径）。 */
    @PostMapping("/material/kng-list")
    public ApiResponse<PageResp> kngList(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestBody(required = false) AiGenDtos.KngListReq req) {
        AiGenRespDtos.KngMaterialItem[] records =
                kngMaterialService.page(page, pageSize, req == null ? null : req.getKeyword());
        PageResp resp = new PageResp();
        resp.setTotal(records.length);
        resp.setRecords(List.of(records));
        return ApiResponse.success(resp);
    }

    /** SG-03c 课件素材抽取。 */
    @GetMapping("/material/kng-extract")
    public ApiResponse<AiGenRespDtos.MaterialExtractResp> kngExtract(
            @RequestParam String coursewareId) {
        return ApiResponse.success(kngMaterialService.extractByCourseware(coursewareId));
    }

    /** SG-04 工作流①场景生成。 */
    @PostMapping("/gen-scene")
    public ApiResponse<AiGenRespDtos.GenSceneResp> genScene(@RequestBody AiGenDtos.GenSceneReq req) {
        return ApiResponse.success(aiGenService.genScene(req));
    }

    /** SG-05 工作流②大纲生成。 */
    @PostMapping("/gen-outline")
    public ApiResponse<AiGenRespDtos.GenOutlineResp> genOutline(@RequestBody AiGenDtos.GenOutlineReq req) {
        return ApiResponse.success(aiGenService.genOutline(req));
    }

    /** SG-07 工作流③话术生成。 */
    @PostMapping("/gen-script-v2")
    public ApiResponse<AiGenRespDtos.GenScriptResp> genScript(@RequestBody AiGenDtos.GenScriptReq req) {
        return ApiResponse.success(aiGenService.genScript(req));
    }

    /** SG-08 导入编排（Java 版 resolve_round_node）。 */
    @PostMapping("/import-script")
    public ApiResponse<AiGenRespDtos.ImportScriptResp> importScript(
            @RequestBody AiGenDtos.ImportScriptReq req) {
        return ApiResponse.success(aiGenService.importScript(req));
    }

    /** 分页载体（沿用课件管理 jobs 分页的平铺风格）。 */
    @lombok.Data
    public static class PageResp {
        private long total;
        private List<AiGenRespDtos.KngMaterialItem> records = new ArrayList<>();
    }
}
