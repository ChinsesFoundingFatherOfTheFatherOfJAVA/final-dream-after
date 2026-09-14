package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("training_project")
public class TrainingProject {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private String description;
    private Integer status;

    private Integer projectType;
    private Integer assignType;
    private String code;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer completionStandard;
    private Integer passScore;
    private Integer passStrategy;
    private Integer passStrategyN;
    private String responsiblePerson;
    private String responsiblePersonName;
    private String coverImage;
    private Integer dialogueMode;
    private String createdBy;
    private String createdByName;
    private String updatedBy;
    private String updatedByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
