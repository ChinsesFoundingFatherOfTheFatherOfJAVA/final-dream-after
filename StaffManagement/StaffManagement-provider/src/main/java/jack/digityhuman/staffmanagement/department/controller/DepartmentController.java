package jack.digityhuman.staffmanagement.department.controller;

import jack.digityhuman.staffmanagement.common.ListVo;
import jack.digityhuman.staffmanagement.department.service.DepartmentService;
import jack.digityhuman.staffmanagement.department.vo.DepartmentNodeVo;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import jack.digityhuman.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 部门树接口（FP-01），员工名册左侧面板与角色人员管理的"选择部门"下拉共用。
 */
@RestController
@RequestMapping("/api/sm/admin/departments")
public class DepartmentController {

    private final DepartmentService departmentService;

    public DepartmentController(DepartmentService departmentService) {
        this.departmentService = departmentService;
    }

    /**
     * 全量部门平铺列表，带各级全部用户数；{@code keyword} 非空时只返回命中节点及其祖先链。
     *
     * <p>例：{@code GET /api/sm/admin/departments/tree?keyword=营销}
     */
    @GetMapping("/tree")
    public ApiResponse<ListVo<DepartmentNodeVo>> tree(@RequestParam(required = false) String keyword) {
        List<SysDepartment> departments = departmentService.listTree(keyword);
        return ApiResponse.ok(ListVo.of(departments.stream().map(DepartmentController::toVo).toList()));
    }

    private static DepartmentNodeVo toVo(SysDepartment dept) {
        DepartmentNodeVo vo = new DepartmentNodeVo();
        vo.setDeptId(String.valueOf(dept.getId()));
        vo.setDeptCode(dept.getDeptCode());
        vo.setDeptName(dept.getDeptName());
        vo.setDeptPath(dept.getDeptPath());
        vo.setParentPath(dept.getParentPath());
        vo.setUserCount(dept.getUserCount());
        return vo;
    }
}
