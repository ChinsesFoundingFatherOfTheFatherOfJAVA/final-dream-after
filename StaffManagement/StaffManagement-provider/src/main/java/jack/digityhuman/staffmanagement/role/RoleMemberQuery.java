package jack.digityhuman.staffmanagement.role;

import jack.digityhuman.staffmanagement.common.BizException;
import lombok.Data;

/**
 * 角色成员列表的查询条件（FP-15）。
 *
 * <p>与员工名册的 {@code UserQuery} 分开：这里不支持岗位多选和超管筛选，
 * 搜索字段也只有姓名/账号两项。用两个不同的类型把"这个页面能按什么筛"说清楚，
 * 比塞一个大而全的查询对象、再在实现里静默忽略多余参数要好。
 */
@Data
public class RoleMemberQuery {

    private Integer page = 1;
    private Integer size = 20;

    /** 部门节点 ID：查该部门<b>含全部子部门</b>的成员。 */
    private String deptId;

    /** 1 启用 / 0 禁用，null 表示全部。 */
    private Integer status;

    /** 搜索字段：name 或 account，缺省按姓名。 */
    private String searchField;

    private String keyword;

    /** 归一化分页与空白参数：越界纠正而不报错。 */
    public void normalize() {
        if (page == null || page < 1) {
            page = 1;
        }
        if (size == null || size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
        if (keyword != null && keyword.isBlank()) {
            keyword = null;
        }
        if (deptId != null && deptId.isBlank()) {
            deptId = null;
        }
    }

    /** 成员列表只按姓名或账号搜，其他字段直接报参数错误，不静默按姓名兜底。 */
    public boolean searchByAccount() {
        if (searchField == null || searchField.isBlank() || "name".equalsIgnoreCase(searchField.trim())) {
            return false;
        }
        if ("account".equalsIgnoreCase(searchField.trim())) {
            return true;
        }
        throw BizException.param("searchField 只支持 name/account，收到: " + searchField);
    }
}
