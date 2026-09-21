package jack.digityhuman.atualcombattraining.common;

import lombok.Getter;

/**
 * 业务异常：抛出后由 GlobalExceptionHandler 统一转为 code=1 的 ApiResponse
 */
@Getter
public class BizException extends RuntimeException {

    private final int code;

    public BizException(String message) {
        super(message);
        this.code = 1;
    }

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }
}
