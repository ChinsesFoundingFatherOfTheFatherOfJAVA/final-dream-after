package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

/** 用户表里的部门路径与直属部门信息，用于同步侧合成部门树。 */
@Data
public class DeptPathInfoRow {

    private String deptPath;
    private String deptCode;
}
