package jack.digityhuman.onlineclassroom.dto;

import java.math.BigDecimal;

/**
 * 课程设置接口 DTO。
 */
public final class KngSettingDtos {

    private KngSettingDtos() {
    }

    public record StandardRule(
            Integer standardMinutes,
            BigDecimal score,
            Boolean applyToAll) {
    }

    public record DocumentRule(
            Integer pagesPerScore,
            BigDecimal score,
            Integer standardMinutes,
            BigDecimal maxScore,
            Boolean applyToAll) {
    }

    public record MicrocourseRule(
            Integer standardMinutes,
            BigDecimal score,
            BigDecimal maxScore,
            Boolean applyToAll) {
    }

    public record ScoreConf(
            StandardRule videoAudio,
            DocumentRule document,
            MicrocourseRule microcourse,
            StandardRule scorm,
            StandardRule html,
            Integer rewardType) {
    }

    public record CheatConf(
            Boolean antiIdleEnabled,
            Integer mode,
            Integer checkMinutes,
            Integer confirmSeconds,
            String promptText,
            Boolean multiCourseEnabled,
            Boolean forbidDragEnabled,
            Boolean audioBackgroundPlay) {
    }

    public record CheatSaveRequest(
            Boolean antiIdleEnabled,
            Integer mode,
            Integer checkMinutes,
            Integer confirmSeconds,
            String promptText,
            Boolean multiCourseEnabled,
            Boolean forbidDragEnabled) {
    }

    public record SpecialRequest(Boolean audioBackgroundPlay) {
    }

    public record PlayConf(Boolean speedEnabled, Integer speedScope, Boolean speedHoursCalc) {
    }

    public record FaceConf(Boolean enabled) {
    }

    public record Response(
            ScoreConf scoreConf,
            CheatConf cheatConf,
            PlayConf playConf,
            FaceConf faceConf) {
    }
}
