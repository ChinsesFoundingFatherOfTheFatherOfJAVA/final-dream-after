package jack.digityhuman.atualcombattraining.common;

import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 兼容多种日期时间格式的反序列化器（Jackson 3，供 Spring MVC 的 HTTP JSON 请求体使用）。
 * <p>
 * Spring Boot 4 的 HTTP 消息转换器使用 Jackson 3（tools.jackson），
 * 因此 Jackson 2（com.fasterxml.jackson）的模块在此不生效，需单独提供本实现。
 * <p>
 * 支持格式：
 * - ISO 格式：2026-09-11T00:00:00
 * - 空格分隔：2026-09-11 00:00:00
 * - 仅日期：2026-09-11
 */
public class FlexibleLocalDateTimeJackson3Deserializer extends ValueDeserializer<LocalDateTime> {

    private static final DateTimeFormatter SPACE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) {
        String text = p.getString();
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (Exception ignored) {
            // try next format
        }
        try {
            return LocalDateTime.parse(value, SPACE_FORMAT);
        } catch (Exception ignored) {
            // try next format
        }
        return LocalDate.parse(value, DATE_ONLY).atStartOfDay();
    }
}
