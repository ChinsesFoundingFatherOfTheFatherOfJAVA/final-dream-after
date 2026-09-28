package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

import java.util.List;

@Data
public class ProjectStudentQueryReq {

    private String keyword;
    private Integer finishStatus;
    private List<String> deptIds;
    private List<String> postIds;
    private Integer accountStatus;
    private Integer page = 1;
    private Integer pageSize = 10;
}
