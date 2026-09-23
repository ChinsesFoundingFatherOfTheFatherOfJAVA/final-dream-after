package jack.digityhuman.staffmanagement.web;

import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.common.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 参数错误直接返回 400 和中文说明；其余异常只回一句通用提示，堆栈留在日志里，不外泄。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务失败：HTTP 200 + {@code {code, message}}，由前端按 code 分支处理。
     *
     * <p>必须排在通用 handler 之前被匹配到——Spring 按异常类型精确度选处理器，
     * {@link BizException} 比 {@link Exception} 更精确，所以这里不需要额外排序；
     * 但不能把它交给下面的 {@link #serverError}，否则 40101/40902 这类语义码会被抹平成 500。
     */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> bizException(BizException ex) {
        log.warn("业务异常 code={}: {}", ex.getCode(), ex.getMessage());
        return ApiResponse.error(ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> badRequest(IllegalArgumentException ex) {
        return ApiResponse.error(HttpStatus.BAD_REQUEST.value(), ex.getMessage());
    }

    /**
     * 参数根本没绑定成功：{@code size} 超出 int 范围、{@code status} 传了 {@code LEFT} 这类
     * 非数字、必填参数缺失、查询串里有无法按 UTF-8 解码的字节等。
     *
     * <p>必须单独列出来：本类的 {@link #serverError} 能匹配任何异常，优先级高于 Spring 内置的
     * 400 处理，不显式接住就会把客户端的格式错误报成 500，既误导调用方也污染服务端错误率。
     *
     * <p>注意异常类型的分工：{@code @ModelAttribute} 对象（如 {@code UserQuery}）的绑定失败抛
     * {@link MethodArgumentNotValidException}，方法参数直挂 {@code @RequestParam} 才抛
     * {@link MethodArgumentTypeMismatchException}，两者都得在这儿接住。
     * 按《03》§1.4 一律回 40001 + HTTP 200，前端才读得到 {@code data.code}。
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class,
            MethodArgumentNotValidException.class, BindException.class})
    public ApiResponse<Void> malformedParameter(Exception ex) {
        log.warn("请求参数无法绑定: {}", ex.getMessage());
        return ApiResponse.error(ErrorCode.PARAM_ERROR.getCode(), "请求参数格式不正确，请检查参数类型与取值范围");
    }

    /**
     * 请求体读不出来：JSON 语法错、字段类型对不上、编码不是 UTF-8。
     *
     * <p>和参数绑定失败同类，都是调用方的错，按《03》§1.4 走 40001 + HTTP 200——
     * 前端的 request.ts 只在 2xx 里读 {@code data.code}，回 400 会让它落到 axios 的通用错误分支，
     * 用户看到的就是一句"网络异常"而不是"参数不正确"。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ApiResponse<Void> malformedBody(HttpMessageNotReadableException ex) {
        log.warn("请求体无法解析: {}", ex.getMessage());
        return ApiResponse.error(ErrorCode.PARAM_ERROR.getCode(), "请求体格式不正确，需为合法 JSON");
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> serverError(Exception ex) {
        log.error("接口未处理异常", ex);
        return ApiResponse.error(ErrorCode.SERVER_ERROR.getCode(), "服务器内部错误，请稍后重试");
    }
}
