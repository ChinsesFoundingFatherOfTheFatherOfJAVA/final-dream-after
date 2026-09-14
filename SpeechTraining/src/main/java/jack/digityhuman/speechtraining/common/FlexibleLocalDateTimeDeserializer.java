package jack.digityhuman.speechtraining.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 兼容多种日期时间格式的反序列化器：
 * - ISO 格式：2026-09-11T00:00:00
 * - 空格分隔：2026-09-11 00:00:00
 * - 仅日期：2026-09-11
 */
public class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    private static final DateTimeFormatter SPACE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        String text = p.getText();
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
        return java.time.LocalDate.parse(value, DATE_ONLY).atStartOfDay();
    }
}
