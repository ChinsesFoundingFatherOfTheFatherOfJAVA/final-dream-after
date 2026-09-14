package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

@Data
public class TemplateSaveReq {

    private String name;
    private String description;
    private Integer permission;
}
