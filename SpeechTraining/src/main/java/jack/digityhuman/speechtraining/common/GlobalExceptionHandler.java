package jack.digityhuman.speechtraining.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getMessage());
    }

    /**
     * 请求了不存在的路径。不单独处理会被下面的兜底按"系统异常"打 error 堆栈 + 返回 500，
     * 排查时会把"URL 写错"当成后端挂了。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("接口不存在: {}", e.getResourcePath());
        ApiResponse<Void> resp = new ApiResponse<>();
        resp.setCode(404);
        resp.setMessage("接口不存在: " + e.getResourcePath());
        return resp;
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
