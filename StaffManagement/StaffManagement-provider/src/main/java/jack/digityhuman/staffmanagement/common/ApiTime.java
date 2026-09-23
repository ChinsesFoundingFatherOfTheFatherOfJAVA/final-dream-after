package jack.digityhuman.staffmanagement.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 出参时间格式化。
 *
 * <p>接口约定日期是 {@code "2026-09-18"}、日期时间是 {@code "2026-09-23 15:20:00"}
 * （《03-接口文档》§1.6）。与其依赖 Jackson 对 {@code LocalDateTime} 的默认写法、
 * 再在每个字段上挂序列化注解，不如在服务层直接转成字符串：口径唯一，
 * 也免得 Boot 4 换到 Jackson 3 之后默认格式又变。无值返回 null，由前端渲染 "-"。
 */
public final class ApiTime {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ApiTime() {
    }

    public static String date(LocalDate value) {
        return value == null ? null : DATE.format(value);
    }

    public static String dateTime(LocalDateTime value) {
        return value == null ? null : DATE_TIME.format(value);
    }
}
