package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用分页结果。
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private int page;

    /** 每页条数 */
    private int size;

    /** 满足条件的总条数 */
    private long total;

    /** 当前页记录 */
    private List<T> records = new ArrayList<>();

    public static <T> PageResult<T> of(int page, int size, long total, List<T> records) {
        PageResult<T> result = new PageResult<>();
        result.setPage(page);
        result.setSize(size);
        result.setTotal(total);
        result.setRecords(records == null ? new ArrayList<>() : records);
        return result;
    }
}
