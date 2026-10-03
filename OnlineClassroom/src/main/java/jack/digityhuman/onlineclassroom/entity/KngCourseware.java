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
    /** 视频宽(px)（MT-01：merge 时 ffprobe 探测，发布保存时随表单透传写入；非媒体类为 NULL） */
    private Integer mediaWidth;
    /** 视频高(px)（同上） */
    private Integer mediaHeight;
    /** 编码格式（ffprobe codec_name，如 h264/aac） */
    private String mediaCodec;
    /** HLS m3u8 相对路径（MT-02：转码成功后写入 /kng-files/transcode/{id}/index.m3u8） */
    private String hlsPath;
    /** 字幕已生成（MT-03：四语言 SRT 齐全后置 1；字幕管理页与编辑抽屉的开关依据） */
    private Integer subtitlesFlag;
    private String catalogName;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
