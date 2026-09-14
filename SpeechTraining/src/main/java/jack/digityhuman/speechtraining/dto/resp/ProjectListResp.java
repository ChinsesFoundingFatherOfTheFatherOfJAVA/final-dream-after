package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProjectListResp {

    private Long id;
    private String name;
    private Integer projectType;
    private Integer status;
    private Integer assignType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String responsiblePersonName;
    private String coverImage;
    private LocalDateTime createdAt;
}
