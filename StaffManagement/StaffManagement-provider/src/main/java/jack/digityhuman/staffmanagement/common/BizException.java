package jack.digityhuman.staffmanagement.common;

/**
 * 业务异常（本应用）：在 after-end-common 的统一 {@link jack.digityhuman.common.api.BizException}
 * 之上补充 {@link ErrorCode} 枚举构造器，code 语义与《03-接口文档》§1.4 错误码总表一致。
 *
 * <p>统一处理器（common 的 {@code GlobalExceptionHandler}）捕获的是父类型，本子类实例同样命中。
 */
public class BizException extends jack.digityhuman.common.api.BizException {

    public BizException(ErrorCode errorCode) {
        super(errorCode.getCode(), errorCode.getMessage());
    }

    /** 带上下文的失败，如"该角色下仍有 28 名用户，请先取消全部授权"。 */
    public BizException(ErrorCode errorCode, String message) {
        super(errorCode.getCode(), message);
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
