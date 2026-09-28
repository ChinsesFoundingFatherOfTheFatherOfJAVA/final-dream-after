package jack.digityhuman.speechtraining.dto.req;

import lombok.Data;

import java.util.List;

@Data
public class InviteStudentsReq {

    private List<String> userIds;
}
