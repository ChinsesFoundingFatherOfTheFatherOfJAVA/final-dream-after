package jack.digityhuman.staffmanagement.notification.vo;

import lombok.Data;

/** 学员端通知列表查询条件（04 §8 API-44）。 */
@Data
public class NoticeQuery {

    /** -1 全部 0 未读 1 已读 */
    private Integer readStatus = -1;

    /** 可选：按来源服务过滤，考试场景传 exam */
    private String sourceService;

    private Integer current = 1;

    private Integer limit = 20;

    /** 用户输入容错：limit 上限 100，防止一次拖全表。 */
    public int safeCurrent() {
        return current == null || current < 1 ? 1 : current;
    }

    public int safeLimit() {
        if (limit == null || limit < 1) {
            return 20;
        }
        return Math.min(limit, 100);
    }
}
