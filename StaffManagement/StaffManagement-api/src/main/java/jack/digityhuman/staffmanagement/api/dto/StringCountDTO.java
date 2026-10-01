package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 通用"字符串维度 + 计数"一行，用于按部门路径 / 按岗位分组的用户数统计。
 */
@Data
public class StringCountDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 分组维度值：部门全路径（{@code dept_path}）或岗位名（{@code position_name}） */
    private String key;

    private long count;
}
