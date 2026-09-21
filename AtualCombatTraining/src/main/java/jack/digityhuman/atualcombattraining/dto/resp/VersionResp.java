package jack.digityhuman.atualcombattraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 版本历史项响应（FP-19，按 version_no 倒序，新在上）
 */
@Data
public class VersionResp {

    /** 版本号（字符串序列化防前端精度丢失） */
    private String versionNo;
    /** 是否当前版本（唯一 true） */
    private Boolean current;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;
    /** 占位统计：使用人数 */
    private Integer usedUserCount;
    /** 占位统计：已完成（绿） */
    private Integer completedCount;
    /** 占位统计：进行中（蓝） */
    private Integer inProgressCount;
    /** 创建者（当前写死"管理员"） */
    private String creator;
}
