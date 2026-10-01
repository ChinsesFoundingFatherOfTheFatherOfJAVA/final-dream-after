package jack.digityhuman.common.api;

import lombok.Data;

/**
 * 全站统一响应结构：{@code {code, message, data}}。
 *
 * <p>约定（见 D:\JACK\plan\后端架构总纲.md §4）：{@code code=0} 成功；业务失败 HTTP 200 + 非零 code，
 * {@code message} 为可直接展示给用户的中文；{@code data} 业务数据，失败时通常为 null。
 * 前端 request.ts 只在 HTTP 2xx 分支里读 {@code data.code}，所以这里永不改 HTTP 状态码。
 */
@Data
public class ApiResponse<T> {

    /** 成功码。 */
    public static final int SUCCESS = 0;

    /** 未命中更具体处理器的业务失败默认码。 */
    public static final int DEFAULT_ERROR = 1;

    private int code;
    private String message;
    private T data;

    /** 成功响应（无数据体）。 */
    public static <T> ApiResponse<T> success() {
        return success(null);
    }

    /** 成功响应（携带数据体）。 */
    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(SUCCESS);
        resp.setMessage("success");
        resp.setData(data);
        return resp;
    }

    /**
     * 成功响应的别名，与 StaffManagement 历史口径 {@code ApiResponse.ok(...)} 对齐；
     * 新代码建议用 {@link #success(Object)}。
     */
    public static <T> ApiResponse<T> ok(T data) {
        return success(data);
    }

    /** 业务失败（code=1），message 为用户可读中文。 */
    public static <T> ApiResponse<T> error(String message) {
        return error(DEFAULT_ERROR, message);
    }

    /** 业务失败（自定义错误码，如 40101/40001/40901 等分段码）。 */
    public static <T> ApiResponse<T> error(int code, String message) {
        ApiResponse<T> resp = new ApiResponse<>();
        resp.setCode(code);
        resp.setMessage(message);
        return resp;
    }
}
