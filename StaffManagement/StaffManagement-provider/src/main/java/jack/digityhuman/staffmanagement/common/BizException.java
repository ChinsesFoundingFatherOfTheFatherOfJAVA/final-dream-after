package jack.digityhuman.staffmanagement.common;

/**
 * 业务异常：由 {@code GlobalExceptionHandler} 转成 HTTP 200 + {@code {code, message}} 响应体。
 *
 * <p>刻意不改 HTTP 状态码：前端 request.ts 的响应拦截器只在 2xx 分支里读 {@code data.code}，
 * 非 2xx 会被 axios 直接 reject 掉，40101「清凭证跳登录」这类约定就永远触发不了。
 * SpeechTraining 也是同一套口径。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /** 带上下文的失败，如"该角色下仍有 28 名用户，请先取消全部授权"。 */
    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }

    /** 抛出一个"不存在"，用于 id 查不到行的统一口径；调用方传 "角色 123" 这类"类型 + 值"。 */
    public static BizException notFound(String what) {
        return new BizException(ErrorCode.NOT_FOUND, what + " 不存在");
    }

    /** 抛出一个"参数不合规"，用于表单校验。 */
    public static BizException param(String message) {
        return new BizException(ErrorCode.PARAM_ERROR, message);
    }
}
