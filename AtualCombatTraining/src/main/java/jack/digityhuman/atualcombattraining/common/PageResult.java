package jack.digityhuman.atualcombattraining.common;

import lombok.Data;

import java.util.List;

/**
 * 分页结果封装（records + total）
 */
@Data
public class PageResult<T> {

    private List<T> records;
    private long total;

    public PageResult(List<T> records, long total) {
        this.records = records;
        this.total = total;
    }
}
