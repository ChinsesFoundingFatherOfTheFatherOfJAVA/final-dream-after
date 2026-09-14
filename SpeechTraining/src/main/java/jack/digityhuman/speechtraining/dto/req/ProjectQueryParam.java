package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

@Data
public class ProjectQueryParam {

    private Integer page = 1;
    private Integer pageSize = 20;
    private Integer status;
    private Integer projectType;
    private String responsible;
    private String keyword;
}
