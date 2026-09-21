package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

@Data
public class AiScriptGenReq {

    private String description;
    private Integer rounds;

    /** 先说话的一方：trainer=陪练者先说（默认，大节点为"陪练者问学员说"），student=学员先说（大节点为"学员问陪练者说"） */
    private String firstSpeaker;
}
