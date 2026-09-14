package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

@Data
public class AiScoringResp {

    private List<String> hitPoints;
    private Integer hitCount;
    private Integer totalCount;
    private String comment;
    private Boolean passed;
}
