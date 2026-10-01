package jack.digityhuman.common.api;

import lombok.Data;

import java.util.List;

/**
 * 通用分页结果，JSON 形如 {@code {records: [...], total: n}}。
 *
 * <p>这是 SpeechTraining / AtualCombatTraining 线上前端已消费的形状；
 * 《后端架构总纲》里约定的 {@code {list,total,page,size}} 形状（StaffManagement PageVo）
 * 与之并存，二者统一需前端配合改造，未完成前不要单方面改字段名。
 */
@Data
public class PageResult<T> {

    private List<T> records;
    private long total;

    public PageResult(List<T> records, long total) {
        this.records = records;
        this.total = total;
    }

    public static <T> PageResult<T> of(List<T> records, long total) {
        return new PageResult<>(records, total);
    }
}
