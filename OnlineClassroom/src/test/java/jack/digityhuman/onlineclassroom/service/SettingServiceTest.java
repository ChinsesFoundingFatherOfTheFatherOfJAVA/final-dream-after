package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.common.BizException;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.mapper.KngSettingMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SettingServiceTest {

    @Test
    void rejectsPromptLongerThan250Characters() {
        KngSettingMapper mapper = mock(KngSettingMapper.class);
        when(mapper.selectById(1)).thenReturn(null);
        when(mapper.insert(any(KngSetting.class))).thenReturn(1);
        SettingService service = new SettingService(mapper);

        String text = "a".repeat(251);
        assertThatThrownBy(() -> service.save(new KngSettingDtos.SaveRequest(
                1, true, 1, 3, 120, text), "1"))
                .isInstanceOf(BizException.class)
                .hasMessage("提示语不能超过 250 字");
    }
}
