package jack.digityhuman.staffmanagement.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 当前登录人的最小身份契约，字段名与前端登录态保持一致。 */
@Data
@AllArgsConstructor
public class MeVo {
    private String userId;
    private String fullname;
    private List<String> permissions;
}
