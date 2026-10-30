package jack.digityhuman.atualcombattraining.controller;

import jack.digityhuman.atualcombattraining.service.StudentAdminService;
import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.PageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 主题学员管理接口（契约 04 §7，API-AD01~AD06）
 * <p>
 * 路径 /api/act/admin/theme/{themeId}/students/**。
 */
@RestController
@RequestMapping("/api/act/admin/theme/{themeId}/students")
@RequiredArgsConstructor
public class StudentAdminController {

    private final StudentAdminService studentAdminService;

    /** AD01 学员列表（汇总 + 分页 + 过滤） */
    @GetMapping
    public ApiResponse<PageResult<Map<String, Object>>> list(
            @PathVariable String themeId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer studyMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studentAdminService.listStudents(
                themeId, keyword, source, status, studyMode, page, Math.min(size, 100)));
    }

    /** AD02 添加学员（唯一键防重复；已移除用户恢复） */
    @PostMapping
    public ApiResponse<Map<String, Integer>> add(
            @PathVariable String themeId,
            @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> userIds = (List<String>) body.getOrDefault("userIds", List.of());
        String source = (String) body.getOrDefault("source", "assigned");
        return ApiResponse.success(studentAdminService.addStudents(themeId, userIds, source));
    }

    /** AD03 调整考核次数（绝对值设置 + 审计；仅影响之后的考核 attempt） */
    @PatchMapping("/{userId}/exam-quota")
    public ApiResponse<Void> adjustExamQuota(
            @PathVariable String themeId,
            @PathVariable String userId,
            @RequestBody Map<String, Object> body) {
        int extra = body.get("extraAttemptCount") instanceof Number number ? number.intValue() : 0;
        studentAdminService.adjustExamQuota(themeId, userId, extra,
                (String) body.getOrDefault("reason", ""));
        return ApiResponse.success(null);
    }

    /** AD04 移出主题（软删除；活动 attempt 存在时拒绝） */
    @DeleteMapping("/{userId}")
    public ApiResponse<Void> remove(@PathVariable String themeId, @PathVariable String userId) {
        studentAdminService.removeStudent(themeId, userId);
        return ApiResponse.success(null);
    }

    /** AD05 学员详情（汇总 + 场景分布 + 最近活跃） */
    @GetMapping("/{userId}")
    public ApiResponse<Map<String, Object>> detail(
            @PathVariable String themeId, @PathVariable String userId) {
        return ApiResponse.success(studentAdminService.studentDetail(themeId, userId));
    }

    /** AD06 学员对练记录（attempt 分页 + 报告摘要） */
    @GetMapping("/{userId}/records")
    public ApiResponse<PageResult<Map<String, Object>>> records(
            @PathVariable String themeId,
            @PathVariable String userId,
            @RequestParam(required = false) Integer studyMode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(studentAdminService.studentRecords(
                themeId, userId, studyMode, page, Math.min(size, 100)));
    }
}
