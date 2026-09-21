package jack.digityhuman.atualcombattraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 主题列表项响应（FP-06，卡片字段）
 */
@Data
public class ThemeListResp {

    private String id;
    private String themeName;
    private String themeDesc;
    /** 发布状态：0草稿 1发布中 2发布失败 3已发布 4已暂停 5已结束 */
    private Integer status;
    /** 主题模式：1日常训练 2通关考核 3训考一体 */
    private Integer themeMode;
    /** 来源：1单独安排 2项目安排（占位展示） */
    private Integer sourceType;
    /** 参与人数（占位统计） */
    private Integer participantCount;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
