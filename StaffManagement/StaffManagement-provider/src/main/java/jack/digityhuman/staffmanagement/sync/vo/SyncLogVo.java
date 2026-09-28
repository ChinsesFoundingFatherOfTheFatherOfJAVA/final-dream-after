package jack.digityhuman.staffmanagement.sync.vo;

import lombok.Data;

/**
 * 同步记录（《03-接口文档》§2.7 同步状态查询）。
 *
 * <p>只给排查用的事实：本轮类型、推进到的水位、各项计数与成败。水位为 null 表示库里还没有
 * 任何一次成功的同步（首次上线前），前端渲染 "-"。
 */
@Data
public class SyncLogVo {

    private String syncType;
    private String watermark;
    private Integer total;
    private Integer inserted;
    private Integer updated;
    private Integer deleted;
    private Integer skipped;
    private String status;
    private String errorMsg;
    private String startedAt;
    private String finishedAt;
}
