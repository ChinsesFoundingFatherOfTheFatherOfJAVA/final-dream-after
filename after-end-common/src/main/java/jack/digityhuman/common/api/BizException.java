package jack.digityhuman.common.api;

import lombok.Getter;

/**
 * 业务异常：抛出后由 {@code jack.digityhuman.common.web.GlobalExceptionHandler}
 * 统一转成 HTTP 200 + {@code {code, message}} 响应体。
 *
 * <p>刻意不改 HTTP 状态码：前端 request.ts 的响应拦截器只在 2xx 分支里读 {@code data.code}，
 * 非 2xx 会被 axios 直接 reject，40101「清凭证跳登录」这类约定就触发不了。
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    /** 业务失败，code=1（默认失败码），message 为用户可读中文。 */
    public BizException(String message) {
        super(message);
        this.code = ApiResponse.DEFAULT_ERROR;
    }

    /** 业务失败，带分段错误码（401xx 登录 / 403xx 权限 / 400xx 参数 / 409xx 冲突 / 503xx 依赖）。 */
    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
