package jack.digityhuman.staffmanagement.role.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量添加 / 批量取消成员的请求体（FP-16 / FP-15）。
 */
@Data
public class UserIdsRequest {

    /** 用户 ID 列表，字符串传输；上限在服务层校验。 */
    private List<String> userIds;
}
