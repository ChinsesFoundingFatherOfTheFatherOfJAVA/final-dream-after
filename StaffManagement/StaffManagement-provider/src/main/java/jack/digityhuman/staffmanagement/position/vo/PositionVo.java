package jack.digityhuman.staffmanagement.position.vo;

import lombok.Data;

/** 岗位选择弹窗的一行（FP-03）：岗位名 + 该岗位的用户数。 */
@Data
public class PositionVo {

    private String positionName;
    private long userCount;
}
