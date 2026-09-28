package jack.digityhuman.staffmanagement.common;

/**
 * 业务错误码，与《03-接口文档》§1.4 错误码总表一一对应。
 *
 * <p>{@code code=0} 是成功，由 {@code ApiResponse.ok} 使用；这里只列失败码。
 * 前端 request.ts 依据 40101 清凭证跳登录页，其余码直接展示 message。
 */
public enum ErrorCode {

    /** 参数错误：缺少必填项、格式非法。 */
    PARAM_ERROR(40001, "请求参数不正确"),

    /** 未登录或 token 失效——前端据此清除本地凭证并跳 /login。 */
    UNAUTHORIZED(40101, "登录已失效，请重新登录"),

    /** 账号或密码错误（刻意不区分，避免账号枚举）。 */
    BAD_CREDENTIALS(40102, "账号或密码错误"),

    /** 账号被禁用。 */
    ACCOUNT_DISABLED(40103, "账号已被禁用，请联系管理员"),

    /** 非超管调用超管专属接口。 */
    FORBIDDEN(40301, "无权限执行该操作"),

    /** 用户/角色/权限 ID 无效。 */
    NOT_FOUND(40401, "数据不存在"),

    /** 角色名称重复。 */
    ROLE_NAME_EXISTS(40901, "角色名称已存在"),

    /** 角色下仍有授权用户。 */
    ROLE_IN_USE(40902, "该角色下仍有用户，请先取消全部授权"),

    /** 内置超管保护（禁用/删除/取消超管）。 */
    BUILTIN_ADMIN_PROTECTED(40903, "内置超级管理员账号禁止该操作"),

    /** 修改密码时原密码不符。 */
    OLD_PASSWORD_WRONG(40904, "原密码错误"),

    /** 用户仍是训练项目负责人/创建者。 */
    USER_PROJECT_RESPONSIBLE(40905, "该用户仍是训练项目负责人，请先处理项目"),

    /** 服务器内部错误。 */
    SERVER_ERROR(50001, "服务器内部错误，请稍后重试"),

    /** Oracle 同步失败。 */
    SYNC_FAILED(50301, "Oracle 同步失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
