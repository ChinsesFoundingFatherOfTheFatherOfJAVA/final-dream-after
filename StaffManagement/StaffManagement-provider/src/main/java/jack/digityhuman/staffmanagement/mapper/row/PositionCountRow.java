package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

/** 岗位 distinct 结果：岗位名 + 该岗位的用户数（FP-03 岗位弹窗）。 */
@Data
public class PositionCountRow {

    private String positionName;
    private long userCount;
}
