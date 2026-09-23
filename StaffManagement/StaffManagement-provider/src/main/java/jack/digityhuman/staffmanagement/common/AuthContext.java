package jack.digityhuman.staffmanagement.common;

/**
 * 当前登录人上下文，由 {@code JwtFilter} 在校验通过后写入、请求结束时清除。
 *
 * <p>token 是无状态的，每个请求都要重新解析，所以这里用 ThreadLocal 而不是 session。
 * 只放"每条业务写操作都要落库"的三个字段：操作人 ID、账号、姓名，以及超管标识；
 * 权限码列表不进 token（签发后改权限不会即时生效，且会撑大 token），需要时按需查库。
 */
public final class AuthContext {

    /** 一次请求的登录人信息。 */
    public record Principal(String userId, String account, String name, boolean superAdmin) {
    }

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** 取当前登录人；过滤器未放行时为 null。 */
    public static Principal get() {
        return CURRENT.get();
    }

    /** 取当前登录人账号，用于 createdBy / addedBy 这类审计字段。 */
    public static String account() {
        Principal principal = CURRENT.get();
        return principal == null ? null : principal.account();
    }

    /** 当前是否超级管理员。 */
    public static boolean isSuperAdmin() {
        Principal principal = CURRENT.get();
        return principal != null && principal.superAdmin();
    }
}
