package jack.digityhuman.staffmanagement.api.query;

import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页与状态口径的公共基类。两个查询入参都带这几项，避免在各自类里重复。
 */
@Getter
@Setter
public abstract class PagedQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 每页最大条数，超过会被截断，防止一次拉走整个子树 */
    public static final int MAX_SIZE = 500;

    /** 在职状态口径，为 null 时按 {@link WorkStatus#ACTIVE} 处理 */
    private WorkStatus status;

    /** 页码，从 1 开始 */
    private int page = 1;

    /** 每页条数，为 0 表示只取统计与层级结构、不取员工明细 */
    private int size = 20;

    /** 是否返回身份证号、手机号等敏感字段，默认不返回 */
    private boolean includeSensitive;

    /** 归一化入参：兜底默认值，并把页码页长夹到安全区间。 */
    public void normalize() {
        if (status == null) {
            status = WorkStatus.ACTIVE;
        }
        if (page < 1) {
            page = 1;
        }
        if (size < 0) {
            size = 0;
        }
        if (size > MAX_SIZE) {
            size = MAX_SIZE;
        }
    }
}
