package jack.digityhuman.staffmanagement.common;

import lombok.Data;

import java.util.List;

/**
 * 管理端分页响应体，形如 {@code { "list": [...], "total": 978, "page": 1, "size": 20 }}。
 *
 * <p>与 {@code StaffManagement-api} 里的 {@code PageResult} 不是一个东西：
 * 那个是 Dubbo 契约（字段名 records），改动会影响消费方；这个是本期 HTTP 接口的约定，
 * 前端按 {@code list} 取数据（《03-接口文档》§1.3）。
 */
@Data
public class PageVo<T> {

    private List<T> list;
    private long total;
    private int page;
    private int size;

    public static <T> PageVo<T> of(List<T> list, long total, int page, int size) {
        PageVo<T> vo = new PageVo<>();
        vo.list = list;
        vo.total = total;
        vo.page = page;
        vo.size = size;
        return vo;
    }
}
