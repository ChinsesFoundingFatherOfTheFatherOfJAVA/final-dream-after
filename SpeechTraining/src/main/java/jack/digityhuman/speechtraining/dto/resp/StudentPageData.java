package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 学员端通用分页响应（含分页元信息，接口文档 1.1 约定）
 */
@Data
public class StudentPageData<T> {

    private List<T> records;
    private long total;
    private long page;
    private long pageSize;

    public StudentPageData(List<T> records, long total, long page, long pageSize) {
        this.records = records;
        this.total = total;
        this.page = page;
        this.pageSize = pageSize;
    }
}
