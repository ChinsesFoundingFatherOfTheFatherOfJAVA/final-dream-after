package jack.digityhuman.staffmanagement.web;

import lombok.Data;

/**
 * 统一响应包装。{@code code=0} 表示成功，非 0 时 {@code message} 是可直接展示给用户的中文说明。
 *
 * @param <T> 业务数据类型
 */
@Data
public class ApiResponse<T> {

    public static final int SUCCESS = 0;

    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = SUCCESS;
        response.message = "ok";
        response.data = data;
        return response;
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.code = code;
        response.message = message;
        return response;
    }
}
