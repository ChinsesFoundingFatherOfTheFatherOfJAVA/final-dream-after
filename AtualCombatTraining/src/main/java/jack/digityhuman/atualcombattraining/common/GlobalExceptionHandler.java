package jack.digityhuman.atualcombattraining.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;

/**
 * 全局异常处理：业务异常返回 code=1 + 中文提示，未知异常返回 code=500
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常: {}", e.getMessage());
        return ApiResponse.error(e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        // 超过 multipart 上限属用户可理解的条件，返回 code=1 而非 500，避免前端只见"服务器内部错误"
        log.warn("上传文件超过大小上限: {}", e.getMessage());
        return ApiResponse.error("上传文件超过大小上限，请压缩后重试");
    }

    /**
     * multipart 请求体无法解析。
     * <p>
     * 实测最常见的成因：前端手写了 <code>Content-Type: multipart/form-data</code> 而没带 boundary
     * （正确做法是不写这个头，交给浏览器补 <code>boundary=...</code>）。
     * 这类属客户端协议错误，明确点出原因——早期只回"服务器内部错误"，排查很费时。
     * 注：MaxUploadSizeExceededException 是它的子类，Spring 会优先匹配上面更具体的处理器。
     */
    @ExceptionHandler(MultipartException.class)
    public ApiResponse<Void> handleMultipart(MultipartException e) {
        log.warn("multipart 请求解析失败: {}", e.getMessage());
        return ApiResponse.error("上传请求格式不正确（multipart 缺少 boundary）：请勿手写 Content-Type，交由浏览器设置");
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
