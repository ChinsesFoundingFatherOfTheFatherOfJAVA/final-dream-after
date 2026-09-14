package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

@Data
public class AiScoringReq {

    private String standardScript;
    private String keyPoints;
    private String passCondition;
    private String userInput;
}
