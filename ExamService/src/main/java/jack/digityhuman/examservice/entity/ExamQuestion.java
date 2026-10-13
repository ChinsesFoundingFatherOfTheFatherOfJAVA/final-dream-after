package jack.digityhuman.examservice.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 试题（六题型统一表；组合题 type=5，子题 parent_id 指向父题）。 */
@Data
@TableName("exam_question")
public class ExamQuestion {


    /** 0单选 1多选 2判断 3填空 4问答 5组合 */
    public static final int TYPE_SINGLE = 0;
    public static final int TYPE_MULTI = 1;
    public static final int TYPE_JUDGE = 2;
    public static final int TYPE_FILL = 3;
    public static final int TYPE_ANSWER = 4;
    public static final int TYPE_COMPOSITE = 5;

    @TableId(type = IdType.ASSIGN_ID)
    private String id;
    private String libId;
    /** 组合题父题 id（子题非空） */
    private String parentId;
    /** 子题在父题内排序（父题为0） */
    private Integer orderIndex;
    private Integer type;
    /** 0易 1中 2难 */
    private Integer levelType;
    /** 0已保存 1已提交 2已审核 3不通过（列表不展示） */
    private Integer status;
    /** 题干HTML（富文本） */
    private String content;
    /** 题干纯文本（列表/查看） */
    private String summary;
    /** 归一化题干（查重：去空白/HTML） */
    private String summaryNorm;
    /** 题干媒体 JSON {fileId,fileUrl,fileType} */
    private String stemPlay;
    private String explainText;
    private String explainPlay;
    /** 判断题答案 0正确 1错误 */
    private Integer judgeAnswer;
    private String judgeCorrectOptionContent;
    private String judgeWrongOptionContent;
    /** 填空(按行)/问答题答案 */
    private String answerContent;
    private String answerPlay;
    /** 问答题关键词 JSON [{keyword,scoreRatio}] */
    private String answerKeywords;
    /** 填空题多空答案不分先后 */
    private Integer noOrderMark;
    /** 查重重复标记 */
    private Integer duplicate;
    private Long simHash;
    private Integer disabled;
    @TableLogic
    private Integer deleted;
    private String createdBy;
    private String createdByName;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;

}
