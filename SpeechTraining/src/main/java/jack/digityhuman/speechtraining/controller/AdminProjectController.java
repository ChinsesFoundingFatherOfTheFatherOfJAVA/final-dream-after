package jack.digityhuman.speechtraining.controller;

import jack.digityhuman.speechtraining.common.ApiResponse;
import jack.digityhuman.speechtraining.common.FileStorageService;
import jack.digityhuman.speechtraining.common.PageResult;
import jack.digityhuman.speechtraining.common.ProjectFormParser;
import jack.digityhuman.speechtraining.dto.req.FlowSaveReq;
import jack.digityhuman.speechtraining.dto.req.ProjectQueryParam;
import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import jack.digityhuman.speechtraining.dto.resp.FlowDataResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectDetailResp;
import jack.digityhuman.speechtraining.dto.resp.ProjectListResp;
import jack.digityhuman.speechtraining.service.TrainingProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/admin/training-project")
@RequiredArgsConstructor
public class AdminProjectController {

    private final TrainingProjectService projectService;
    private final FileStorageService fileStorageService;
    private final ProjectFormParser projectFormParser;

    @GetMapping("/list")
    public ApiResponse<PageResult<ProjectListResp>> list(ProjectQueryParam param) {
        return ApiResponse.success(projectService.listProjects(param));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProjectDetailResp> detail(@PathVariable Long id) {
        return ApiResponse.success(projectService.getProjectDetail(id));
    }

    /** 创建项目（JSON 方式） */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Long> create(@RequestBody ProjectSaveReq req) {
        return ApiResponse.success(projectService.createProject(req));
    }

    /** 创建项目（FormData 方式，支持上传封面与陪练者形象图片） */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Long> createWithCover(MultipartHttpServletRequest request) {
        ProjectSaveReq req = projectFormParser.parse(request);
        applyUploadedImages(req, request);
        return ApiResponse.success(projectService.createProject(req));
    }

    /** 更新项目（JSON 方式） */
    @PutMapping(value = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Void> update(@PathVariable Long id, @RequestBody ProjectSaveReq req) {
        projectService.updateProject(id, req);
        return ApiResponse.success();
    }

    /** 更新项目（FormData 方式，支持上传封面与陪练者形象图片） */
    @PutMapping(value = "/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Void> updateWithCover(@PathVariable Long id, MultipartHttpServletRequest request) {
        ProjectSaveReq req = projectFormParser.parse(request);
        applyUploadedImages(req, request);
        projectService.updateProject(id, req);
        return ApiResponse.success();
    }

    /**
     * 本地文件优先于 JSON 中已有的图片 URL；封面与形象图只认各自的专用字段。
     */
    private void applyUploadedImages(ProjectSaveReq req, MultipartHttpServletRequest request) {
        MultipartFile coverFile = projectFormParser.extractFile(request);
        if (coverFile != null) {
            req.setCoverImage(fileStorageService.saveCoverImage(coverFile));
        }

        MultipartFile avatarFile = request.getFile("avatarImage");
        if (avatarFile != null && !avatarFile.isEmpty()) {
            ProjectSaveReq.VirtualTrainerReq trainer = req.getVirtualTrainer();
            if (trainer == null) {
                trainer = new ProjectSaveReq.VirtualTrainerReq();
                req.setVirtualTrainer(trainer);
            }
            trainer.setAvatarImage(fileStorageService.saveAvatarImage(avatarFile));
        }
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<Void> publish(@PathVariable Long id) {
        projectService.publishProject(id);
        return ApiResponse.success();
    }

    @PostMapping("/{id}/withdraw")
    public ApiResponse<Void> withdraw(@PathVariable Long id) {
        projectService.withdrawProject(id);
        return ApiResponse.success();
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        projectService.deleteProject(id);
        return ApiResponse.success();
    }

    @GetMapping("/{id}/nodes")
    public ApiResponse<FlowDataResp> getNodes(@PathVariable Long id) {
        return ApiResponse.success(projectService.getFlowData(id));
    }

    @PostMapping("/{id}/nodes")
    public ApiResponse<Void> saveNodes(@PathVariable Long id, @RequestBody FlowSaveReq req) {
        projectService.saveFlow(id, req);
        return ApiResponse.success();
    }
}
