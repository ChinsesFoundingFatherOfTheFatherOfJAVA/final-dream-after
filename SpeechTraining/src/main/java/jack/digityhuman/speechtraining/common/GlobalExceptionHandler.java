package jack.digityhuman.speechtraining.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        ApiResponse<Void> resp = new ApiResponse<>();
        resp.setCode(500);
        resp.setMessage("服务器内部错误");
        return resp;
    }
}
