package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("kng_study_record")
public class KngStudyRecord {

    @TableId(type = IdType.INPUT)
    private String id;
    private String coursewareId;
    private String userId;
    private String deptId;
    private String post;
    private Integer studyStatus;
    private LocalDateTime startStudyTime;
    private LocalDateTime endStudyTime;
    private BigDecimal studyLenHours;
    private Integer finishCostDays;
    private Integer finishMethod;
    /** 学习进度%（课程内学习详情页新增列，学员端写入，管理端只读展示，当前恒 null） */
    private BigDecimal studySchedule;
    private LocalDateTime createdAt;
}
