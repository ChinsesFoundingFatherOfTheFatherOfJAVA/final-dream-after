package jack.digityhuman.onlineclassroom.dto;

/**
 * 课程设置接口 DTO。
 */
public final class KngSettingDtos {

    private KngSettingDtos() {
    }

    public record SaveRequest(
            Integer creditMode,
            Boolean antiIdleEnabled,
            Integer antiIdleMode,
            Integer checkIntervalMinutes,
            Integer confirmSeconds,
            String promptText) {
    }

    public record Response(
            Integer creditMode,
            Boolean antiIdleEnabled,
            Integer antiIdleMode,
            Integer checkIntervalMinutes,
            Integer confirmSeconds,
            String promptText) {
    }
}
