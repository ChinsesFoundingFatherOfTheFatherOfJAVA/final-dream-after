package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

@Data
public class TemplateQueryParam {

    private Integer page = 1;
    private Integer pageSize = 20;
    private Integer status;
    private String keyword;
}
