package jack.digityhuman.staffmanagement.api.query;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;

/**
 * 员工模糊查询入参。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class EmployeeSearchQuery extends PagedQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 关键字，同时模糊匹配中文姓名(NAME)、英文姓名(ENNAME)与工号(GH) */
    private String keyword;
}
