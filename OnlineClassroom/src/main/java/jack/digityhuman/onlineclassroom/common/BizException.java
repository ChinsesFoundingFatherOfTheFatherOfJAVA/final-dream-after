package jack.digityhuman.onlineclassroom.common;

/**
 * 可展示给用户的业务异常。
 */
public class BizException extends RuntimeException {

    public BizException(String message) {
        super(message);
    }
}
