package jack.digityhuman.onlineclassroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.mapper.KngSettingMapper;
import jack.digityhuman.onlineclassroom.service.impl.SettingServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettingServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void returnsCodeDefaultsWhenRowIsMissing() {
        KngSettingMapper mapper = mock(KngSettingMapper.class);
        when(mapper.selectById(1)).thenReturn(null);
        SettingService service = new SettingServiceImpl(mapper);

        KngSettingDtos.Response response = service.getAll();

        assertThat(response.scoreConf().videoAudio().standardMinutes()).isEqualTo(10);
        assertThat(response.scoreConf().document().maxScore()).isEqualByComparingTo("6.0");
        assertThat(response.cheatConf().antiIdleEnabled()).isTrue();
        assertThat(response.cheatConf().audioBackgroundPlay()).isFalse();
        assertThat(response.playConf().speedScope()).isEqualTo(2);
        assertThat(response.faceConf().enabled()).isFalse();
    }

    @Test
    void rejectsMicrocourseMaxScoreBelowSingleScore() {
        KngSettingMapper mapper = mock(KngSettingMapper.class);
        SettingService service = new SettingServiceImpl(mapper);

        KngSettingDtos.ScoreConf request = new KngSettingDtos.ScoreConf(
                new KngSettingDtos.StandardRule(10, BigDecimal.ONE, false),
                new KngSettingDtos.DocumentRule(1, new BigDecimal("0.1"), 1, new BigDecimal("6.0"), false),
                new KngSettingDtos.MicrocourseRule(10, BigDecimal.ONE, new BigDecimal("0.5"), false),
                new KngSettingDtos.StandardRule(10, BigDecimal.ONE, false),
                new KngSettingDtos.StandardRule(10, BigDecimal.ONE, false),
                0);

        assertThatThrownBy(() -> service.saveScore(request, "1"))
                .isInstanceOf(BizException.class)
                .hasMessage("微课最高学分不能低于单次学分");
    }

    @Test
    void rejectsPromptLongerThan250Characters() {
        KngSettingMapper mapper = mock(KngSettingMapper.class);
        when(mapper.selectById(1)).thenReturn(null);
        when(mapper.updateById(any(KngSetting.class))).thenReturn(1);
        SettingService service = new SettingServiceImpl(mapper);

        String text = "a".repeat(251);
        assertThatThrownBy(() -> service.saveCheat(
                new KngSettingDtos.CheatSaveRequest(true, 1, 3, 120, text, true, true),
                "1"))
                .isInstanceOf(BizException.class)
                .hasMessage("提示语不能超过 250 字");
    }

    @Test
    void cheatSavePreservesIndependentAudioBackgroundSetting() throws Exception {
        KngSetting existing = new KngSetting();
        existing.setId(1);
        existing.setCheatConf("""
                {"antiIdleEnabled":true,"mode":1,"checkMinutes":3,"confirmSeconds":120,
                 "promptText":"old","multiCourseEnabled":true,"forbidDragEnabled":true,
                 "audioBackgroundPlay":true}
                """);
        KngSettingMapper mapper = mock(KngSettingMapper.class);
        when(mapper.selectById(1)).thenReturn(existing);
        when(mapper.updateById(any(KngSetting.class))).thenReturn(1);
        SettingService service = new SettingServiceImpl(mapper);

        service.saveCheat(
                new KngSettingDtos.CheatSaveRequest(true, 2, 5, 90, "new", false, false),
                "1");

        ArgumentCaptor<KngSetting> captor = ArgumentCaptor.forClass(KngSetting.class);
        verify(mapper).updateById(captor.capture());
        assertThat(JSON.readTree(captor.getValue().getCheatConf()).get("audioBackgroundPlay").asBoolean())
                .isTrue();
        assertThat(JSON.readTree(captor.getValue().getCheatConf()).get("checkMinutes").asInt())
                .isEqualTo(5);
    }
}
