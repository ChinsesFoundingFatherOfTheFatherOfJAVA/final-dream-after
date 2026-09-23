package jack.digityhuman.staffmanagement.web;

import jack.digityhuman.staffmanagement.api.StaffQueryService;
import jack.digityhuman.staffmanagement.api.dto.DepartmentStaffDTO;
import jack.digityhuman.staffmanagement.api.dto.EmployeeDTO;
import jack.digityhuman.staffmanagement.api.dto.PageResult;
import jack.digityhuman.staffmanagement.api.query.DepartmentStaffQuery;
import jack.digityhuman.staffmanagement.api.query.EmployeeSearchQuery;
import jack.digityhuman.staffmanagement.api.query.WorkStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 员工信息查询的 HTTP 接口，与 Dubbo 服务共用同一套业务实现。
 *
 * <p>两个接口都要回一次 Oracle 视图，单次约 2~3 秒，属于已知成本；
 * 部门接口传 {@code size=0} 可以完全走 Redis 缓存，不查库。
 */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

    private final StaffQueryService staffQueryService;

    public StaffController(StaffQueryService staffQueryService) {
        this.staffQueryService = staffQueryService;
    }

    /**
     * 按名称模糊查询员工，命中中文姓名、英文姓名或工号。
     *
     * <p>例：{@code GET /api/staff/employees?keyword=张伟&page=1&size=20}
     */
    @GetMapping("/employees")
    public ApiResponse<PageResult<EmployeeDTO>> searchEmployees(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean includeSensitive) {

        EmployeeSearchQuery query = new EmployeeSearchQuery();
        query.setKeyword(keyword);
        query.setStatus(parseStatus(status));
        query.setPage(page);
        query.setSize(size);
        query.setIncludeSensitive(includeSensitive);
        return ApiResponse.ok(staffQueryService.searchEmployees(query));
    }

    /**
     * 查询指定部门及其全部下级部门的员工，同时返回部门层级树。
     *
     * <p>{@code deptCode} 与 {@code deptName} 至少给一个，前者优先；按名称查到重名部门时会返回 400 并列出候选编号。
     * {@code size=0} 表示只要层级树和人数统计，不取员工明细。
     *
     * <p>例：{@code GET /api/staff/departments/employees?deptCode=10201&size=50}
     */
    @GetMapping("/departments/employees")
    public ApiResponse<DepartmentStaffDTO> listDepartmentEmployees(
            @RequestParam(required = false) String deptCode,
            @RequestParam(required = false) String deptName,
            @RequestParam(required = false, defaultValue = "ACTIVE") String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean includeSensitive) {

        DepartmentStaffQuery query = new DepartmentStaffQuery();
        query.setDeptCode(deptCode);
        query.setDeptName(deptName);
        query.setStatus(parseStatus(status));
        query.setPage(page);
        query.setSize(size);
        query.setIncludeSensitive(includeSensitive);
        return ApiResponse.ok(staffQueryService.listDepartmentStaff(query));
    }

    /** HTTP 侧用字符串传口径，忽略大小写；非法值直接报 400。 */
    private static WorkStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return WorkStatus.ACTIVE;
        }
        try {
            return WorkStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("status 只支持 ACTIVE / LEFT / ALL，收到: " + status);
        }
    }
}
