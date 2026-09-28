package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_user")
public class StaffUser {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String account;
    private String employeeNo;
    private String name;
    private String deptCode;
    private String deptName;
    private String deptPath;
    private String positionName;
    private Integer status;
}
