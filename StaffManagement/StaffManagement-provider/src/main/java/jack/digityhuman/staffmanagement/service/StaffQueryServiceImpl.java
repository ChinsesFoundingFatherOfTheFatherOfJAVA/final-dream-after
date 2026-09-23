package jack.digityhuman.staffmanagement.service;

import jack.digityhuman.staffmanagement.api.StaffQueryService;
import jack.digityhuman.staffmanagement.api.dto.DepartmentNodeDTO;
import jack.digityhuman.staffmanagement.api.dto.DepartmentStaffDTO;
import jack.digityhuman.staffmanagement.api.dto.EmployeeDTO;
import jack.digityhuman.staffmanagement.api.dto.PageResult;
import jack.digityhuman.staffmanagement.api.query.DepartmentStaffQuery;
import jack.digityhuman.staffmanagement.api.query.EmployeeSearchQuery;
import jack.digityhuman.staffmanagement.api.query.WorkStatus;
import jack.digityhuman.staffmanagement.dal.DepartmentStat;
import jack.digityhuman.staffmanagement.dal.StaffViewRepository;
import org.apache.dubbo.config.annotation.DubboService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 员工查询服务的实现，同时是 Dubbo 服务提供者。
 *
 * <p>Web 层只是这层的薄封装，业务规则（口径默认值、部门定位、层级还原）全部收敛在这里，
 * 这样 HTTP 调用方和 Dubbo 消费方看到的语义完全一致。
 */
@DubboService(version = "1.0.0", timeout = 30_000)
public class StaffQueryServiceImpl implements StaffQueryService {

    private static final Logger log = LoggerFactory.getLogger(StaffQueryServiceImpl.class);

    private final StaffViewRepository repository;
    private final DepartmentDirectoryService directoryService;

    public StaffQueryServiceImpl(StaffViewRepository repository, DepartmentDirectoryService directoryService) {
        this.repository = repository;
        this.directoryService = directoryService;
    }

    @Override
    public PageResult<EmployeeDTO> searchEmployees(EmployeeSearchQuery query) {
        EmployeeSearchQuery safe = query == null ? new EmployeeSearchQuery() : query;
        if (safe.getKeyword() == null || safe.getKeyword().isBlank()) {
            throw new IllegalArgumentException("keyword 不能为空：模糊查询必须给关键字，避免全表拉取 34173 行");
        }
        safe.normalize();
        PageResult<EmployeeDTO> result = repository.searchEmployees(
                safe.getKeyword(), safe.getStatus(), safe.getPage(), safe.getSize(), safe.isIncludeSensitive());
        log.info("模糊查询员工 keyword={}, status={}, page={}/{} 命中 {} 条",
                safe.getKeyword(), safe.getStatus(), safe.getPage(), safe.getSize(), result.getTotal());
        return result;
    }

    @Override
    public DepartmentStaffDTO listDepartmentStaff(DepartmentStaffQuery query) {
        DepartmentStaffQuery safe = query == null ? new DepartmentStaffQuery() : query;
        safe.normalize();
        DepartmentStat dept = resolveDepartment(safe);
        WorkStatus status = safe.getStatus();

        DepartmentNodeDTO tree = directoryService.buildSubtree(dept, status);

        DepartmentStaffDTO result = new DepartmentStaffDTO();
        result.setDeptCode(dept.code());
        result.setDeptName(dept.name());
        result.setDeptPath(dept.path());
        result.setLevel(tree.getLevel());
        result.setDepartmentCount(directoryService.countDepartments(tree));
        result.setTotalEmployeeCount(tree.getTotalEmployeeCount());
        result.setTree(tree);

        // size=0 表示只要结构和人数：此时一次库都不用回，纯缓存返回
        if (safe.getSize() == 0) {
            result.setEmployees(PageResult.of(safe.getPage(), 0, tree.getTotalEmployeeCount(), List.of()));
        } else {
            result.setEmployees(repository.findByDeptPath(dept.path(), status,
                    safe.getPage(), safe.getSize(), safe.isIncludeSensitive()));
        }
        log.info("查询部门子树 dept={}({}), status={}, 部门数={}, 员工数={}",
                dept.name(), dept.code(), status, result.getDepartmentCount(), result.getTotalEmployeeCount());
        return result;
    }

    /**
     * 定位部门：编号优先，其次名称；名称重名时不猜，直接把候选抛回去。
     */
    private DepartmentStat resolveDepartment(DepartmentStaffQuery query) {
        String code = trimToNull(query.getDeptCode());
        if (code != null) {
            DepartmentStat byCode = directoryService.requireByCode(code);
            if (byCode == null) {
                throw new IllegalArgumentException("组织编号不存在: " + code);
            }
            return byCode;
        }
        String name = trimToNull(query.getDeptName());
        if (name == null) {
            throw new IllegalArgumentException("deptCode 与 deptName 至少给一个");
        }
        List<DepartmentStat> candidates = directoryService.findByName(name);
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("组织名称不存在: " + name);
        }
        if (candidates.size() > 1) {
            String detail = candidates.stream()
                    .map(c -> c.code() + "=" + c.path())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException("组织名称 \"" + name + "\" 有 " + candidates.size()
                    + " 个重名部门，请改用组织编号查询。候选: " + detail);
        }
        return candidates.get(0);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
