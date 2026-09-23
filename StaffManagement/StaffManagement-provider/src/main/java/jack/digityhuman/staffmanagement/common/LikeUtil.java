package jack.digityhuman.staffmanagement.common;

/**
 * LIKE 模式转义。
 *
 * <p>MySQL 的 {@code LIKE} 里 {@code %} 与 {@code _} 是通配符、{@code \} 是默认转义符。
 * 用户在搜索框里输入 "_" 本意是匹配字面下划线，不转义就会命中所有单字符，
 * 员工名册里搜 "1_2" 这种关键字会返回一堆无关行。部门全路径本身又是以 "_" 拼接的，
 * 前缀匹配不转义更是直接错。
 */
public final class LikeUtil {

    private LikeUtil() {
    }

    /** 把用户输入变成可安全嵌入 LIKE 模式的字面量（配合 MySQL 默认的反斜杠转义符）。 */
    public static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
