package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TemplateDetailResp {

    private Long id;
    private String name;
    private String description;
    private Integer permission;
    private Integer status;
    private String createdByName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
