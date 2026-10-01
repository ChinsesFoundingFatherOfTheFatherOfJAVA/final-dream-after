package jack.digityhuman.common.web;

import jack.digityhuman.common.api.ApiResponse;
import jack.digityhuman.common.api.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全站统一全局异常处理（after-end-common 提供，所有业务应用共用）。
 *
 * <p>所有分支都返回 HTTP 200 + {@code ApiResponse{code,message}}（业务失败不走 HTTP 状态码，
 * 前端 request.ts 只在 2xx 分支里读 code，见 {@link BizException} 说明）。
 * 合并自各应用历史版本的处理器：ST 的 404 处理、ACT 的 multipart 友好提示、SM 的参数绑定 40001。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("业务异常 code={}: {}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /**
     * 请求了不存在的路径。不单独处理会被下面的兜底按"系统异常"打 error 堆栈，
     * 排查时会把"URL 写错"当成后端挂了。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ApiResponse<Void> handleNoResource(NoResourceFoundException e) {
        log.warn("接口不存在: {}", e.getResourcePath());
        return ApiResponse.error(404, "接口不存在: " + e.getResourcePath());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ApiResponse<Void> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        // 超过 multipart 上限属用户可理解的条件，返回 code=1 而非 500，避免前端只见"服务器内部错误"
        log.warn("上传文件超过大小上限: {}", e.getMessage());
        return ApiResponse.error("上传文件超过大小上限，请压缩后重试");
    }

    /**
     * multipart 请求体无法解析。实测最常见的成因：前端手写了
     * {@code Content-Type: multipart/form-data} 而没带 boundary。
     * 注：MaxUploadSizeExceededException 是它的子类，Spring 会优先匹配上面更具体的处理器。
     */
    @ExceptionHandler(MultipartException.class)
    public ApiResponse<Void> handleMultipart(MultipartException e) {
        log.warn("multipart 请求解析失败: {}", e.getMessage());
        return ApiResponse.error("上传请求格式不正确（multipart 缺少 boundary）：请勿手写 Content-Type，交由浏览器设置");
    }

    /**
     * 参数绑定失败：{@code @ModelAttribute} 对象校验失败、{@code @RequestParam} 类型不匹配或缺失等。
     * 按《组织架构与角色权限模块 03-接口文档》§1.4 回 40001 + HTTP 200，前端才读得到 {@code data.code}。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class,
            BindException.class})
    public ApiResponse<Void> malformedParameter(Exception e) {
        log.warn("请求参数无法绑定: {}", e.getMessage());
        return ApiResponse.error(40001, "请求参数格式不正确，请检查参数类型与取值范围");
    }

    /** 请求体读不出来：JSON 语法错、字段类型对不上。同为调用方的错，走 40001 + HTTP 200。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> malformedBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ApiResponse.error(40001, "请求体格式不正确，需为合法 JSON");
    }

    /** IllegalArgumentException 视为业务规则不满足，message 直接透出（各 service 的既定用法）。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("业务参数不合规: {}", e.getMessage());
        return ApiResponse.error(e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return ApiResponse.error(500, "服务器内部错误");
    }
}
