package jack.digityhuman.staffmanagement.notification.vo;

import lombok.Data;

/** 未读数响应（08 §5.1）。 */
@Data
public class UnreadCountVo {

    private long count;

    public static UnreadCountVo of(long count) {
        UnreadCountVo vo = new UnreadCountVo();
        vo.count = count;
        return vo;
    }
}
