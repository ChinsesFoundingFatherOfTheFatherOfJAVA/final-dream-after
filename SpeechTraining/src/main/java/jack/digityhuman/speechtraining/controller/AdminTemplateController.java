package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.TemplateQueryParam;
import jack.digityhuman.speechtraining.dto.req.TemplateSaveReq;
import jack.digityhuman.speechtraining.dto.resp.FlowDataResp;
import jack.digityhuman.speechtraining.dto.resp.TemplateDetailResp;
import jack.digityhuman.speechtraining.dto.resp.TemplateListResp;
import jack.digityhuman.speechtraining.service.SpeechTemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/speech-template")
@RequiredArgsConstructor
public class AdminTemplateController {

    private final SpeechTemplateService templateService;

    @GetMapping("/list")
    public ApiResponse<PageResult<TemplateListResp>> list(TemplateQueryParam param) {
        return ApiResponse.success(templateService.listTemplates(param));
    }

    @GetMapping("/{id}")
    public ApiResponse<TemplateDetailResp> detail(@PathVariable Long id) {
        return ApiResponse.success(templateService.getTemplateDetail(id));
    }

    @PostMapping
    public ApiResponse<Long> create(@RequestBody TemplateSaveReq req) {
        return ApiResponse.success(templateService.createTemplate(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody TemplateSaveReq req) {
        templateService.updateTemplate(id, req);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        templateService.deleteTemplate(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/nodes")
    public ApiResponse<FlowDataResp> getNodes(@PathVariable Long id) {
        return ApiResponse.success(templateService.getFlowData(id));
    }

    @PostMapping("/{id}/nodes")
    public ApiResponse<Void> saveNodes(@PathVariable Long id, @RequestBody FlowSaveReq req) {
        templateService.saveFlow(id, req);
        return ApiResponse.success();
    }
}
