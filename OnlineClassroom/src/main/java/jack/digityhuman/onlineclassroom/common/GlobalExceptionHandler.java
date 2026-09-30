package jack.digityhuman.onlineclassroom.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一将业务异常转为 code=1 响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBiz(BizException exception) {
        log.warn("在线课堂业务异常: {}", exception.getMessage());
        return ApiResponse.error(exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknown(Exception exception) {
        log.error("在线课堂系统异常", exception);
        ApiResponse<Void> response = new ApiResponse<>();
        response.setCode(500);
        response.setMessage("服务器内部错误");
        return response;
    }
}
