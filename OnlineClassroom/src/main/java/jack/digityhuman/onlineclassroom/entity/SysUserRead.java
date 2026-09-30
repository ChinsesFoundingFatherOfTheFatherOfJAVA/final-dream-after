package jack.digityhuman.onlineclassroom.entity;

import lombok.Data;

@Data
public class SysUserRead {
    private Long id;
    private String account;
    private String name;
    private Integer status;
    private String deptPath;
    private String deptName;
    private String positionName;
}
