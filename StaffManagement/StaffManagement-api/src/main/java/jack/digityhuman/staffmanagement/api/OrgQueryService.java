package jack.digityhuman.staffmanagement.api;

import jack.digityhuman.staffmanagement.api.dto.AuthSnapshotDTO;
import jack.digityhuman.staffmanagement.api.dto.RoleUserCountDTO;
import jack.digityhuman.staffmanagement.api.dto.StringCountDTO;
import jack.digityhuman.staffmanagement.api.dto.SysDepartmentDTO;
import jack.digityhuman.staffmanagement.api.dto.SysRoleDTO;
import jack.digityhuman.staffmanagement.api.dto.SysUserDTO;
import jack.digityhuman.staffmanagement.api.dto.PageResult;

import java.util.List;

/**
 * 组织架构与权限查询服务（Dubbo 服务契约）。
 *
 * <p>数据源是 MySQL {@code final_dream} 库的 {@code sys_*} 表（由 StaffManagement 的
 * Oracle 同步任务维护），只读，不含任何写操作。其他应用需要用户/部门/角色/权限数据时，
 * 一律经本服务获取，<b>不得</b>直连 {@code sys_*} 表自建 Mapper——那是 StaffManagement 的领域数据，
 * 直读会让表结构变更同时打穿多个服务。
 *
 * <p>消费方只需依赖 StaffManagement-api 这一个 jar，不必引入本应用的任何运行时。
 */
public interface OrgQueryService {

    // ==================== 用户 ====================

    /**
     * 按账号查用户（不限在职状态）。
     *
     * @param account 登录账号；空/null 返回 null
     */
    SysUserDTO getUserByAccount(String account);

    /** 按账号批量查用户，返回顺序不保证；给定的账号集不去重、不过滤不存在项。 */
    List<SysUserDTO> listUsersByAccounts(List<String> accounts);

    /** 按用户 id 批量查用户，返回顺序不保证。 */
    List<SysUserDTO> listUsersByIds(List<Long> ids);

    /**
     * 按姓名或账号模糊反查用户 id 集合（学习记录等场景按人筛选的前置）。
     *
     * @param keyword 匹配 {@code name} 或 {@code account}；空/null 返回空集合
     */
    List<Long> listUserIdsByKeyword(String keyword);

    /**
     * 拉取在职候选用户（选人组件数据源）。
     *
     * @param deptPath 部门全路径前缀（含该部门及其全部下级部门）；null/空 表示不限部门
     * @param keyword  模糊匹配姓名 / 账号 / 工号；null/空 表示不限
     */
    List<SysUserDTO> listActiveCandidates(String deptPath, String keyword);

    /**
     * 账号鉴权快照：一次调用带回用户、角色 id 与权限码全链。
     *
     * <p>消费方用它替代"按账号查用户 → 查角色 → 查角色权限 → 查权限码"的逐级查表。
     * 账号不存在时 {@code userId} 为 null 且两个集合为空。
     */
    AuthSnapshotDTO getAuthSnapshot(String account);

    // ==================== 部门 ====================

    /** 按部门 id 查单个部门；不存在返回 null。 */
    SysDepartmentDTO getDepartment(Long deptId);

    /** 全量部门，按 {@code sort, id} 升序（部门总量约 1400，一次全量在消费方内存里建树）。 */
    List<SysDepartmentDTO> listAllDepartments();

    /** 按部门 id 批量查部门。 */
    List<SysDepartmentDTO> listDepartmentsByIds(List<Long> ids);

    /** 一级部门（{@code dept_level=1}），按 {@code sort, id} 升序。 */
    List<SysDepartmentDTO> listFirstLevelDepartments();

    // ==================== 角色与统计 ====================

    /**
     * 角色分页（角色总量小，供管理端角色维度页面）。
     *
     * @param keyword 按角色名模糊过滤；null/空 表示不过滤
     */
    PageResult<SysRoleDTO> pageRoles(String keyword, int page, int size);

    /** 按角色 id 集合统计各角色下的用户数；只返回计数 &gt; 0 的角色。 */
    List<RoleUserCountDTO> countUsersByRoleIds(List<Long> roleIds);

    /** 按部门全路径统计启用（{@code status=1}）用户数，供消费方把人数挂到部门树上。 */
    List<StringCountDTO> countUsersGroupByDeptPath();

    /**
     * 岗位维度分页统计（岗位 distinct + 启用用户数）。
     *
     * @param keyword 按岗位名模糊过滤；null/空 表示不过滤
     */
    PageResult<StringCountDTO> pagePositionStats(String keyword, int page, int size);
}
