package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 通知撤销入参（08 §4 cancel）。eventId 与 dedupKey 至少给一个。 */
@Data
public class CancelNotificationCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String eventId;

    private String dedupKey;

    /** 撤销原因，写进投递记录的 failure_reason 便于排查 */
    private String reason;
}
