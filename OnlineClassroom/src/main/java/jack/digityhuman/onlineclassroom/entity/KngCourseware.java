package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("kng_courseware")
public class KngCourseware {

    @TableId(type = IdType.INPUT)
    private String id;
    private String title;
    private Integer type;
    private String catalogId;
    private Integer status;
    private Integer onSale;
    /** 隐藏标记,独立于status（05 增补：更多菜单"隐藏"，可恢复，不影响上下架状态机） */
    private Integer hidden;
    private Integer standardMinutes;
    private Integer standardSeconds;
    private Integer standardDurationFollow;
    private BigDecimal studyScore;
    private Integer finishStandardPercent;
    private String description;
    private String coverUrl;
    private String code;
    private String versionStr;
    private String platformNo;
    private Integer viewPermission;
    private Integer downloadEnabled;
    private Integer downloadPermission;
    private Integer appSaveEnabled;
    private Integer appSavePermission;
    private Integer speedEnabled;
    private Integer speedHoursCalc;
    private Integer aiSubtitleEnabled;
    private String copyOf;
    private String contributorId;
    private String language;
    private String catalogName;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
