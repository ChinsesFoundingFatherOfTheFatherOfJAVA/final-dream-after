package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 部门层级树节点。
 *
 * <p>树由视图里 "组织全名称" 这条 "_" 分隔的全路径还原而来。一个部门只要在组织树里存在就会出现，
 * 哪怕它当前一个员工都没有——这样层级结构才是完整的，不会因为没人就整枝消失。
 */
@Data
public class DepartmentNodeDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 组织编号，部门唯一标识 */
    private String deptCode;

    /** 组织名称（本级名称，不是全路径） */
    private String deptName;

    /** 组织全名称，"_" 分隔的全路径 */
    private String deptPath;

    /** 层级，根为 1 */
    private int level;

    /** 本级直属员工数，按查询的在职口径统计，不含下级部门 */
    private int directEmployeeCount;

    /** 本节点及其全部下级的员工数合计 */
    private int totalEmployeeCount;

    /** 下级部门数（只算直接子节点） */
    private int childCount;

    /** 下级部门 */
    private List<DepartmentNodeDTO> children = new ArrayList<>();
}
