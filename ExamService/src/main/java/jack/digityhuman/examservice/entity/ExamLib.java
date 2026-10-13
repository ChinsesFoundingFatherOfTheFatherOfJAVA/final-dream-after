package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 题库（每租户一个 default_lib=1 的默认库）。 */
@Data
@TableName("exam_lib")
public class ExamLib {


    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String name;
    private String catalogId;
    /** 0公开 1保密（保密库仅创建者及超管可编辑） */
    private Integer secrecy;
    private Integer defaultLib;
    private Integer disabled;
    /** 查重条件位标记 bit0单选..bit4问答（持久化勾选态） */
    private Integer duplicateCondition;
    /** 考核点数（冗余计数，写操作同事务维护） */
    private Integer pointQty;
    /** 试题数（冗余计数，父题计数） */
    private Integer totalQuesQty;
    @TableLogic
    private Integer deleted;
    /** 创建人 userId（转移时变更） */
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;

}
