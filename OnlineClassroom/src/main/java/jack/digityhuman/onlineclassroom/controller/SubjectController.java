package jack.digityhuman.onlineclassroom.controller;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.onlineclassroom.dto.KngSubjectDtos;
import jack.digityhuman.onlineclassroom.service.SubjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 添加用户权限抽屉的数据接口。
 */
@RestController
@RequestMapping("/api/kng/admin/subjects")
@RequiredArgsConstructor
public class SubjectController {

    private final SubjectService subjectService;

    @GetMapping("/dept-tree")
    public ApiResponse<List<KngSubjectDtos.DeptNode>> deptTree() {
        return ApiResponse.success(subjectService.deptTree());
    }

    @GetMapping("/positions")
    public ApiResponse<KngSubjectDtos.PageResult<KngSubjectDtos.PositionRow>> positions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name) {
        return ApiResponse.success(subjectService.positions(page, pageSize, name));
    }

    @GetMapping("/roles")
    public ApiResponse<KngSubjectDtos.PageResult<KngSubjectDtos.RoleRow>> roles(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name) {
        return ApiResponse.success(subjectService.roles(page, pageSize, name));
    }
}
