package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线课堂课程任务（kng_course_task）＝对课件的引用行，字段口径以《04-建表与种子数据.sql》为唯一权威。
 *
 * <p>任务本身不存内容，通过 courseware_id 指向 kng_courseware 取实时状态/学分/时长；
 * 无章节课程 chapter_id=NULL；任务写入时同步维护 kng_ref（ref_type=1，见 CourseRefService/设计器，本实体只读）。
 */
@Data
@TableName("kng_course_task")
public class KngCourseTask {

    /** 雪花 ID（字符串主键） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属课程 kng_course.id */
    private String courseId;

    /** 所属章节 kng_course_chapter.id；NULL=无章节课程 */
    private String chapterId;

    /** 引用的课件 kng_courseware.id */
    private String coursewareId;

    /** 任务名（默认课件名，编辑抽屉可改） */
    private String taskName;

    /** 隐藏标记（学员不可见，可恢复，独立于课件状态） */
    private Integer hidden;

    /** 排序号（仅展示顺序，拖拽排序不做） */
    private Integer orderIndex;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
