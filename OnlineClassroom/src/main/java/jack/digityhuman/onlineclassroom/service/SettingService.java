package jack.digityhuman.onlineclassroom.service;

import jack.digityhuman.onlineclassroom.common.BizException;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.mapper.KngSettingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

/**
 * 课程设置读取与保存。
 */
@Service
@RequiredArgsConstructor
public class SettingService {

    private static final String DEFAULT_PROMPT =
            "已触发防挂机验证，请点击\"继续学习\"，否则学习将被停止";

    private final KngSettingMapper settingMapper;

    public KngSetting get() {
        KngSetting setting = settingMapper.selectById(1);
        if (setting == null) {
            setting = defaultSetting();
            settingMapper.insert(setting);
        }
        return setting;
    }

    @Transactional
    public void save(KngSettingDtos.SaveRequest request, String userId) {
        if (request == null) {
            throw new BizException("请求参数不能为空");
        }
        int mode = request.antiIdleMode() == null ? 1 : request.antiIdleMode();
        if (mode < 1 || mode > 2) {
            throw new BizException("防挂机模式不正确");
        }
        int interval = request.checkIntervalMinutes() == null ? 3 : request.checkIntervalMinutes();
        if (interval < 1 || interval > 60) {
            throw new BizException("确认在线间隔需为 1~60 分钟");
        }
        int seconds = request.confirmSeconds() == null ? 120 : request.confirmSeconds();
        if (mode == 2 && (seconds < 30 || seconds > 600)) {
            throw new BizException("确认时限需为 30~600 秒");
        }
        String prompt = request.promptText() == null ? "" : request.promptText();
        if (prompt.length() > 250) {
            throw new BizException("提示语不能超过 250 字");
        }
        if (Boolean.TRUE.equals(request.antiIdleEnabled()) && !StringUtils.hasText(prompt)) {
            throw new BizException("开启防挂机时提示语不能为空");
        }

        KngSetting setting = get();
        setting.setCreditMode(request.creditMode() == null ? 1 : request.creditMode());
        setting.setAntiIdleEnabled(Boolean.TRUE.equals(request.antiIdleEnabled()) ? 1 : 0);
        setting.setAntiIdleMode(mode);
        setting.setCheckIntervalMinutes(interval);
        setting.setConfirmSeconds(seconds);
        setting.setPromptText(prompt);
        setting.setUpdatedBy(userId);
        setting.setUpdatedAt(LocalDateTime.now());
        settingMapper.updateById(setting);
    }

    private KngSetting defaultSetting() {
        KngSetting setting = new KngSetting();
        setting.setId(1);
        setting.setCreditMode(1);
        setting.setAntiIdleEnabled(0);
        setting.setAntiIdleMode(1);
        setting.setCheckIntervalMinutes(3);
        setting.setConfirmSeconds(120);
        setting.setPromptText(DEFAULT_PROMPT);
        return setting;
    }
}
