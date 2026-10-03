package jack.digityhuman.onlineclassroom.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 在线课堂课程章节（kng_course_chapter），字段口径以《04-建表与种子数据.sql》为唯一权威。
 *
 * <p>章节只承担展示分组职责（学习详情 tab 的"第N章"分组），任务通过 chapter_id 归属章节；
 * 结构由设计器整体保存（全删全插），本表不做单条编辑接口。
 */
@Data
@TableName("kng_course_chapter")
public class KngCourseChapter {

    /** 雪花 ID（字符串主键） */
    @TableId(type = IdType.INPUT)
    private String id;

    /** 所属课程 kng_course.id */
    private String courseId;

    /** 章节名称（必填，≤200 字） */
    private String name;

    /** 章节说明（填了则 ≥5 字） */
    private String description;

    /** 排序号（仅展示顺序，拖拽排序不做） */
    private Integer orderIndex;

    /** 创建时间 */
    private LocalDateTime createdAt;
}
