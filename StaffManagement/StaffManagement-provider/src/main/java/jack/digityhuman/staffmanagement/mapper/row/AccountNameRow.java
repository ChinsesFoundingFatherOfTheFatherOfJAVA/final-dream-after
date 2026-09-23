package jack.digityhuman.staffmanagement.mapper.row;

import lombok.Data;

/** 账号到姓名的映射行，用于把 createdBy / addedBy 这类只存账号的审计字段还原成人姓名。 */
@Data
public class AccountNameRow {

    private String account;
    private String name;
}
