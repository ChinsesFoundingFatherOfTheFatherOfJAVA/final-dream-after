package jack.digityhuman.speechtraining.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 学员端项目卡片（列表页）
 */
@Data
public class ProjectCardResp {

    private Long projectId;
    private String name;
    /** 0=训练（蓝色徽标）/ 1=考核（橙色"考"徽标） */
    private Integer projectType;
    private String coverImage;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;
    /** not_started / ongoing / expired / finished */
    private String status;
    /** 学员是否达标（true 时卡片显示绿色"已完成"徽标） */
    private Boolean completed;
}
