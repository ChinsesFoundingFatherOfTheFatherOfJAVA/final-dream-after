package jack.digityhuman.speechtraining.auth;

/**
 * 当前话术训练请求的登录人上下文。
 */
public final class SpeechAuthContext {

    public record Principal(String userId, String account, String name, boolean superAdmin) {
    }

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private SpeechAuthContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        return CURRENT.get();
    }

    public static String account() {
        Principal principal = CURRENT.get();
        return principal == null ? null : principal.account();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
