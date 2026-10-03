package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.common.CurrentUserService;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngRef;
import jack.digityhuman.onlineclassroom.service.CourseOpService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 课程创建/表单保存与更多菜单操作接口（03-接口文档 2.6/2.9）。
 *
 * <p>与只读查询的 CourseController 同路径前缀但职责分离：本控制器全部为写/操作端点，
 * 用户身份经 CurrentUserService 从 JWT 解出（鉴权口径见 AGENTS.md 铁律 3）。
 */
@RestController
@RequestMapping("/api/kng/admin/course")
@RequiredArgsConstructor
public class CourseOpController {

    private final CourseOpService courseOpService;
    private final CurrentUserService currentUserService;

    /** 创建课程（CS-08 点"下一步"时）：插入未发布记录，返回新课程 id。 */
    @PostMapping
    public ApiResponse<KngCoursewareDtos.IdStatusResponse> create(
            @RequestBody(required = false) KngCourseDtos.CreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                courseOpService.create(request, currentUserService.userId(authorization)));
    }

    /** 保存第一步基本信息表单（CS-07，编辑/创建共用）。 */
    @PutMapping("/{id}/basic")
    public ApiResponse<Void> saveBasic(
            @PathVariable String id,
            @RequestBody(required = false) KngCourseDtos.BasicSaveRequest request) {
        courseOpService.saveBasic(id, request);
        return ApiResponse.success(null);
    }

    /** 隐藏/取消隐藏（CS-09；hidden 与上下架状态互不影响的独立双标记）。 */
    @PostMapping("/{id}/hide")
    public ApiResponse<Void> hide(
            @PathVariable String id,
            @RequestBody(required = false) KngCourseDtos.HiddenRequest request) {
        courseOpService.hide(id, request == null ? null : request.hidden());
        return ApiResponse.success(null);
    }

    /** 下架单课程（仅已发布可下架，状态机口径同课件）。 */
    @PostMapping("/{id}/take-down")
    public ApiResponse<Void> takeDown(@PathVariable String id) {
        courseOpService.takeDown(id);
        return ApiResponse.success(null);
    }

    /** 批量上架（仅已下架课程可重新上架）。 */
    @PostMapping("/batch/put-on")
    public ApiResponse<KngCoursewareDtos.CountResponse> batchPutOn(
            @RequestBody(required = false) KngCoursewareDtos.IdsRequest request) {
        return ApiResponse.success(courseOpService.batchPutOn(request));
    }

    /** 批量下架（仅已发布课程可下架）。 */
    @PostMapping("/batch/take-down")
    public ApiResponse<KngCoursewareDtos.CountResponse> batchTakeDown(
            @RequestBody(required = false) KngCoursewareDtos.IdsRequest request) {
        return ApiResponse.success(courseOpService.batchTakeDown(request));
    }

    /** 批量移动到目标分类。 */
    @PostMapping("/batch/move")
    public ApiResponse<KngCoursewareDtos.CountResponse> move(
            @RequestBody(required = false) KngCoursewareDtos.MoveRequest request) {
        return ApiResponse.success(courseOpService.move(request));
    }

    /** 复制课程（深拷贝章节/任务并重建 ref 行，副本回到未发布）。 */
    @PostMapping("/{id}/copy")
    public ApiResponse<KngCoursewareDtos.IdResponse> copy(
            @PathVariable String id,
            @RequestBody(required = false) KngCoursewareDtos.CopyRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(
                courseOpService.copy(id, request, currentUserService.userId(authorization)));
    }

    /** 删除单课程（被培训/考试引用时阻止，当前恒无；连带删章节/任务并清理 ref 行）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        courseOpService.delete(id);
        return ApiResponse.success(null);
    }

    /** 批量删除（逐个执行引用检查）。 */
    @PostMapping("/batch/delete")
    public ApiResponse<Void> batchDelete(
            @RequestBody(required = false) KngCoursewareDtos.IdsRequest request) {
        courseOpService.batchDelete(request);
        return ApiResponse.success(null);
    }

    /**
     * 课程下载（CS-09 拍板"不做打包"）：恒抛业务异常，由全局异常处理器转
     * code=1"课程无源文件"（原平台为打包任务课件下载，本次范围不做）。
     */
    @GetMapping("/{id}/download")
    public ApiResponse<Void> download(@PathVariable String id) {
        throw new BizException("课程无源文件");
    }

    /** 课程引用记录（CS-09 占位）：查 kng_ref ref_type=2，当前恒空属预期。 */
    @GetMapping("/{id}/references")
    public ApiResponse<List<KngRef>> references(@PathVariable String id) {
        return ApiResponse.success(courseOpService.references(id));
    }
}
