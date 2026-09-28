package jack.digityhuman.speechtraining.dto.resp;

import jack.digityhuman.speechtraining.dto.req.ProjectSaveReq;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProjectDetailResp {

    private Long id;
    private String name;
    private String description;
    private Integer projectType;
    private Integer status;
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
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private List<ProjectSaveReq.ScoringDimensionReq> scoringDimensions;
    private ProjectSaveReq.VirtualTrainerReq virtualTrainer;
}
