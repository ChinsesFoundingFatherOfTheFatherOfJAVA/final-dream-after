package jack.digityhuman.staffmanagement.user;

import lombok.Data;

import java.util.List;

/**
 * 员工名册 / 添加成员弹窗的查询条件（FP-04/06/07/08/11）。
 *
 * <p>条件之间是 AND，{@code positionNames} 内部是 OR（多选岗位命中任一即可）。
 * 由 Spring 从 query string 直接绑定，字段名即接口参数名。
 */
@Data
public class UserQuery {

    /** 页码，从 1 起。 */
    private Integer page = 1;

    /** 每页条数，前端只允许 20/50/100。 */
    private Integer size = 20;

    /** 部门编码：查该部门<b>含全部子部门</b>的用户，按 dept_path 前缀匹配。 */
    private String deptCode;

    /** 1 启用 / 0 禁用，null 表示全部。 */
    private Integer status;

    /** 搜索字段，见 {@link UserSearchField}，缺省按姓名。 */
    private String searchField;

    /** 搜索关键字，对所选字段模糊匹配。 */
    private String keyword;

    /** 岗位名多选，OR 关系。 */
    private List<String> positionNames;

    /** 1 只看超管 / 0 只看非超管，null 表示全部。 */
    private Integer isSuperAdmin;

    /** 归一化分页参数：越界直接纠正，不报错——分页控件不该因为手输 URL 就白屏。 */
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
        if (deptCode != null && deptCode.isBlank()) {
            deptCode = null;
        }
        if (positionNames != null) {
            positionNames = positionNames.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
            if (positionNames.isEmpty()) {
                positionNames = null;
            }
        }
    }
}
