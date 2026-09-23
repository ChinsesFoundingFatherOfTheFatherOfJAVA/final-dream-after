package jack.digityhuman.staffmanagement.department;

import jack.digityhuman.staffmanagement.common.LikeUtil;
import jack.digityhuman.staffmanagement.entity.SysDepartment;
import jack.digityhuman.staffmanagement.mapper.SysDepartmentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jack.digityhuman.staffmanagement.common.BizException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 部门树查询（FP-01，以及 FP-15 的"选择部门"下拉）。
 *
 * <p>接口回的是<b>平铺数组</b>，建树交给前端：全量约 1400 个节点，一次传完比让用户点一层
 * 请求一层要快得多，也回避了"懒加载树上做关键字搜索"这种两头不讨好的设计。
 */
@Service
public class DepartmentService {

    /** 部门全路径分隔符，与 Oracle "组织全名称" 一致。 */
    public static final String PATH_SEPARATOR = "_";

    private final SysDepartmentMapper departmentMapper;

    public DepartmentService(SysDepartmentMapper departmentMapper) {
        this.departmentMapper = departmentMapper;
    }

    /**
     * 按编码取部门，用于把"查该部门含子部门"翻译成路径前缀条件；查不到即 40401。
     */
    public SysDepartment requireByCode(String deptCode) {
        SysDepartment dept = departmentMapper.selectOne(
                Wrappers.<SysDepartment>lambdaQuery().eq(SysDepartment::getDeptCode, deptCode));
        if (dept == null) {
            throw BizException.notFound("部门 " + deptCode);
        }
        return dept;
    }

    /**
     * 子部门的路径 LIKE 模式。
     *
     * <p>路径里的 {@code _} 必须转义：它在 MySQL 的 LIKE 中是单字符通配符，
     * 不转义会让前缀"杰克集团_营销军团"连兄弟部门"杰克集团_营销军团X_…"一起命中。
     * 与等值条件（用户就挂在该部门本身）配合使用才是完整的子树语义。
     */
    public static String childPathPattern(String deptPath) {
        return LikeUtil.escape(deptPath) + "\\" + PATH_SEPARATOR + "%";
    }

    /**
     * 关键字过滤部门树：命中节点及其<b>全部祖先链</b>保留，其余隐藏（FP-01 验收标准）。
     *
     * <p>子节点不保留——搜索"营销"时把整个营销军团的下级全铺出来，等于没搜。
     */
    public List<SysDepartment> listTree(String keyword) {
        List<SysDepartment> all = departmentMapper.selectList(
                Wrappers.<SysDepartment>lambdaQuery().orderByAsc(SysDepartment::getId));
        if (keyword == null || keyword.isBlank()) {
            return all;
        }
        Map<String, SysDepartment> byCode = all.stream()
                .collect(Collectors.toMap(SysDepartment::getDeptCode, Function.identity(), (a, b) -> a));
        String needle = keyword.trim();

        Set<String> keep = new LinkedHashSet<>();
        for (SysDepartment dept : all) {
            if (dept.getDeptName() == null || !dept.getDeptName().contains(needle)) {
                continue;
            }
            keep.add(dept.getDeptCode());
            SysDepartment parent = byCode.get(dept.getParentCode());
            // 逐层上溯把父链补齐；byCode 由库里的 parent_code 构成，理论上无环，
            // 但脏数据（自引用或互为父子）会让这个循环退不出去，所以带上已见判断。
            while (parent != null && keep.add(parent.getDeptCode())) {
                parent = byCode.get(parent.getParentCode());
            }
        }
        List<SysDepartment> filtered = new ArrayList<>(keep.size());
        for (SysDepartment dept : all) {
            if (keep.contains(dept.getDeptCode())) {
                filtered.add(dept);
            }
        }
        filtered.sort(Comparator.comparing(SysDepartment::getDeptLevel, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SysDepartment::getSort, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(SysDepartment::getId));
        return filtered;
    }
}
