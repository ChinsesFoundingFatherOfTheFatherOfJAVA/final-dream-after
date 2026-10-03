package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.service.DesignerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 课程设计器结构整体读写接口（03-接口文档 2.7 CS-07a）。
 *
 * <p>读取与保存共用 /designer 路径；保存为整体 JSON 提交（保存/暂存共用，publish 布尔区分），
 * 无逐操作请求；落库全删全插 + kng_ref 事务内重建（见 DesignerService）。
 */
@RestController
@RequestMapping("/api/kng/admin/course")
@RequiredArgsConstructor
public class CourseDesignerController {

    private final DesignerService designerService;

    /** 读取设计器结构：章节/任务排序 + 任务 join 课件实时值（学分/时长/状态/类型）。 */
    @GetMapping("/{id}/designer")
    public ApiResponse<KngCourseDtos.DesignerView> view(@PathVariable String id) {
        return ApiResponse.success(designerService.view(id));
    }

    /** 保存设计器（【保存】/【暂存关闭】共用）：publish=true 且未发布时课程置已发布。 */
    @PutMapping("/{id}/designer")
    public ApiResponse<Void> save(
            @PathVariable String id,
            @RequestBody(required = false) KngCourseDtos.DesignerSaveRequest request) {
        designerService.save(id, request);
        return ApiResponse.success(null);
    }
}
