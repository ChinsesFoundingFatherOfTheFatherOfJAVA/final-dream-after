package jack.digityhuman.onlineclassroom.entity;

import lombok.Data;

@Data
public class SysDepartmentRead {
    private Long id;
    private String deptCode;
    private String deptName;
    private String parentPath;
    private String deptPath;
    private Integer deptLevel;
    private Integer sort;
    private Integer userCount;
}
