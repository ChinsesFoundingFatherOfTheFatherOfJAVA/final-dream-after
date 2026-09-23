package jack.digityhuman.staffmanagement.common;

import lombok.Data;

import java.util.List;

/**
 * 只有一层 {@code list} 的响应体，对应《03-接口文档》里不分页的列表接口
 * （部门树、角色列表、权限树、职级、同步状态）。
 *
 * <p>包一层而不是直接回数组建，是为了让前端统一按 {@code data.list} 取值，
 * 与分页接口 {@code data.list/total/page/size} 保持同一个形状。
 */
@Data
public class ListVo<T> {

    private List<T> list;

    public static <T> ListVo<T> of(List<T> list) {
        ListVo<T> vo = new ListVo<>();
        vo.list = list;
        return vo;
    }
}
