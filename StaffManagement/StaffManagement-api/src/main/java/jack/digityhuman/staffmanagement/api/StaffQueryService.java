package jack.digityhuman.staffmanagement.api;

import jack.digityhuman.staffmanagement.api.dto.DepartmentStaffDTO;
import jack.digityhuman.staffmanagement.api.dto.EmployeeDTO;
import jack.digityhuman.staffmanagement.api.dto.PageResult;
import jack.digityhuman.staffmanagement.api.query.DepartmentStaffQuery;
import jack.digityhuman.staffmanagement.api.query.EmployeeSearchQuery;

/**
 * 员工信息查询服务（Dubbo 服务契约）。
 *
 * <p>数据源是 Oracle 视图 {@code JACKSHR.V_BD_JACKMES_INFO}，只读，不含任何写操作。
 * 消费方只需依赖 StaffManagement-api 这一个 jar，不必引入本应用的任何运行时。
 */
public interface StaffQueryService {

    /**
     * 按名称模糊查询员工，分页返回。
     *
     * <p>关键字同时匹配中文姓名、英文姓名与工号；在职口径与是否返回敏感字段由入参控制。
     *
     * @param query 查询条件，{@code keyword} 必填
     * @return 分页结果，{@code total} 为满足条件的员工总数
     */
    PageResult<EmployeeDTO> searchEmployees(EmployeeSearchQuery query);

    /**
     * 查询指定部门（含其全部下级部门）的员工，并给出该部门的层级树。
     *
     * <p>部门用 {@code deptCode}（组织编号）定位；只给 {@code deptName} 时按名称解析，
     * 名称在组织树里大量重名，解析到多个部门会抛 {@link IllegalArgumentException} 并列出候选编号。
     *
     * @param query 查询条件，{@code deptCode} 与 {@code deptName} 至少给一个
     * @return 部门层级树 + 子树员工分页列表
     */
    DepartmentStaffDTO listDepartmentStaff(DepartmentStaffQuery query);
}
