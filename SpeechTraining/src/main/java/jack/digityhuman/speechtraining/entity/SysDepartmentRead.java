package jack.digityhuman.speechtraining.entity;

import lombok.Data;

@Data
public class SysDepartmentRead {
    private Long id;
    private String deptName;
    private String parentPath;
    private String deptPath;
    private Integer deptLevel;
    private Integer sort;
}
