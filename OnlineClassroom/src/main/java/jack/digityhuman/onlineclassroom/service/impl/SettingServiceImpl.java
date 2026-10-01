package jack.digityhuman.onlineclassroom.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.onlineclassroom.dto.KngSettingDtos;
import jack.digityhuman.onlineclassroom.entity.KngSetting;
import jack.digityhuman.onlineclassroom.mapper.KngSettingMapper;
import jack.digityhuman.onlineclassroom.service.SettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 课程设置读取与按区块保存。
 */
@Service
@RequiredArgsConstructor
public class SettingServiceImpl implements SettingService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal ZERO_POINT_ONE = new BigDecimal("0.1");
    private static final BigDecimal SIX = new BigDecimal("6.0");
    private static final BigDecimal TEN = new BigDecimal("10.0");
    private static final String DEFAULT_PROMPT =
            "已触发防挂机验证，请点击\"继续学习\"，否则学习将被停止";

    private final KngSettingMapper settingMapper;

    /** 读取课程设置聚合（单行 id=1，区块 JSON 反序列化为各配置对象）。 */
    public KngSettingDtos.Response getAll() {
        KngSetting setting = settingMapper.selectById(1);
        return new KngSettingDtos.Response(
                readScore(setting == null ? null : setting.getScoreConf()),
                readCheat(setting == null ? null : setting.getCheatConf()),
                readPlay(setting == null ? null : setting.getPlayConf()),
                readFace(setting == null ? null : setting.getFaceConf()));
    }

    @Transactional
    /** 保存学分策略区块（study_score 等），独立保存不影响其他区块。 */
    public void saveScore(KngSettingDtos.ScoreConf request, String userId) {
        validateScore(request);
        KngSetting setting = loadRow();
        setting.setScoreConf(toJson(request));
        persist(setting, userId);
    }

    @Transactional
    /** 保存防挂机区块：与旧配置合并校验后整体写回 cheat_conf JSON。 */
    public void saveCheat(KngSettingDtos.CheatSaveRequest request, String userId) {
        KngSetting setting = loadRow();
        KngSettingDtos.CheatConf old = readCheat(setting.getCheatConf());
        KngSettingDtos.CheatConf merged = validateCheat(request, old);
        setting.setCheatConf(toJson(merged));
        persist(setting, userId);
    }

    @Transactional
    /** 保存特殊设置抽屉（在防挂机 JSON 内的独立开关位，合并写回）。 */
    public void saveSpecial(KngSettingDtos.SpecialRequest request, String userId) {
        if (request == null || request.audioBackgroundPlay() == null) {
            throw new BizException("请选择是否允许后台播放");
        }
        KngSetting setting = loadRow();
        KngSettingDtos.CheatConf old = readCheat(setting.getCheatConf());
        setting.setCheatConf(toJson(new KngSettingDtos.CheatConf(
                old.antiIdleEnabled(),
                old.mode(),
                old.checkMinutes(),
                old.confirmSeconds(),
                old.promptText(),
                old.multiCourseEnabled(),
                old.forbidDragEnabled(),
                request.audioBackgroundPlay())));
        persist(setting, userId);
    }

    @Transactional
    /** 保存课程播放区块（倍速/拖拽/续播等播放器行为配置）。 */
    public void savePlay(KngSettingDtos.PlayConf request, String userId) {
        if (request == null || request.speedEnabled() == null) {
            throw new BizException("请选择是否开启倍速播放");
        }
        if (request.speedScope() == null || (request.speedScope() != 1 && request.speedScope() != 2)) {
            throw new BizException("倍速使用范围不正确");
        }
        if (request.speedHoursCalc() == null) {
            throw new BizException("请选择是否开启学时倍速计算");
        }
        KngSetting setting = loadRow();
        setting.setPlayConf(toJson(request));
        persist(setting, userId);
    }

    @Transactional
    /** 保存人脸识别区块（抓拍频次/比对策略等）。 */
    public void saveFace(KngSettingDtos.FaceConf request, String userId) {
        if (request == null || request.enabled() == null) {
            throw new BizException("请选择是否开启人脸识别");
        }
        KngSetting setting = loadRow();
        setting.setFaceConf(toJson(request));
        persist(setting, userId);
    }

    private KngSettingDtos.ScoreConf readScore(String json) {
        return json == null || json.isBlank() ? defaultScore() : fromJson(json, KngSettingDtos.ScoreConf.class);
    }

    private KngSettingDtos.CheatConf readCheat(String json) {
        return json == null || json.isBlank() ? defaultCheat() : fromJson(json, KngSettingDtos.CheatConf.class);
    }

    private KngSettingDtos.PlayConf readPlay(String json) {
        return json == null || json.isBlank() ? defaultPlay() : fromJson(json, KngSettingDtos.PlayConf.class);
    }

    private KngSettingDtos.FaceConf readFace(String json) {
        return json == null || json.isBlank() ? new KngSettingDtos.FaceConf(false) : fromJson(json, KngSettingDtos.FaceConf.class);
    }

    private KngSettingDtos.ScoreConf defaultScore() {
        return new KngSettingDtos.ScoreConf(
                new KngSettingDtos.StandardRule(10, ONE, false),
                new KngSettingDtos.DocumentRule(1, ZERO_POINT_ONE, 1, SIX, false),
                new KngSettingDtos.MicrocourseRule(10, ONE, TEN, false),
                new KngSettingDtos.StandardRule(10, ONE, false),
                new KngSettingDtos.StandardRule(10, ONE, false),
                0);
    }

    private KngSettingDtos.CheatConf defaultCheat() {
        return new KngSettingDtos.CheatConf(
                true, 1, 3, 120, DEFAULT_PROMPT, true, true, false);
    }

    private KngSettingDtos.PlayConf defaultPlay() {
        return new KngSettingDtos.PlayConf(true, 2, true);
    }

    private KngSetting loadRow() {
        KngSetting setting = settingMapper.selectById(1);
        if (setting != null) {
            return setting;
        }
        setting = new KngSetting();
        setting.setId(1);
        setting.setScoreConf(toJson(defaultScore()));
        setting.setCheatConf(toJson(defaultCheat()));
        setting.setPlayConf(toJson(defaultPlay()));
        setting.setFaceConf(toJson(new KngSettingDtos.FaceConf(false)));
        return setting;
    }

    private void persist(KngSetting setting, String userId) {
        setting.setId(1);
        setting.setUpdatedBy(userId);
        setting.setUpdatedAt(LocalDateTime.now());
        if (settingMapper.updateById(setting) == 0) {
            settingMapper.insert(setting);
        }
    }

    private void validateScore(KngSettingDtos.ScoreConf request) {
        if (request == null) {
            throw new BizException("请求参数不能为空");
        }
        validateStandard(request.videoAudio(), "视频/音频");
        validateDocument(request.document());
        validateMicrocourse(request.microcourse());
        validateStandard(request.scorm(), "Scorm");
        validateStandard(request.html(), "HTML");
        if (request.rewardType() == null || (request.rewardType() != 0 && request.rewardType() != 1)) {
            throw new BizException("学分发放时间不正确");
        }
    }

    private void validateStandard(KngSettingDtos.StandardRule rule, String label) {
        if (rule == null) {
            throw new BizException(label + "学分规则不能为空");
        }
        requireMinutes(rule.standardMinutes(), label + "标准时长");
        requireScore(rule.score());
        if (rule.applyToAll() == null) {
            throw new BizException(label + "应用范围不能为空");
        }
    }

    private void validateDocument(KngSettingDtos.DocumentRule rule) {
        if (rule == null) {
            throw new BizException("文档学分规则不能为空");
        }
        if (rule.pagesPerScore() == null || rule.pagesPerScore() < 1 || rule.pagesPerScore() > 1440) {
            throw new BizException("文档页数需在 1~1440 之间");
        }
        requireScore(rule.score());
        requireMinutes(rule.standardMinutes(), "文档标准学习时长");
        requireScore(rule.maxScore());
        if (rule.maxScore().compareTo(rule.score()) < 0) {
            throw new BizException("文档最高学分不能低于每页学分");
        }
        if (rule.applyToAll() == null) {
            throw new BizException("文档应用范围不能为空");
        }
    }

    private void validateMicrocourse(KngSettingDtos.MicrocourseRule rule) {
        if (rule == null) {
            throw new BizException("微课学分规则不能为空");
        }
        requireMinutes(rule.standardMinutes(), "微课标准时长");
        requireScore(rule.score());
        requireScore(rule.maxScore());
        if (rule.maxScore().compareTo(rule.score()) < 0) {
            throw new BizException("微课最高学分不能低于单次学分");
        }
        if (rule.applyToAll() == null) {
            throw new BizException("微课应用范围不能为空");
        }
    }

    private void requireMinutes(Integer value, String label) {
        if (value == null || value < 1 || value > 1440) {
            throw new BizException(label + "需在 1~1440 之间");
        }
    }

    private void requireScore(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("学分需为正数");
        }
        if (value.scale() > 1) {
            throw new BizException("学分最多保留一位小数");
        }
    }

    private KngSettingDtos.CheatConf validateCheat(
            KngSettingDtos.CheatSaveRequest request,
            KngSettingDtos.CheatConf old) {
        if (request == null) {
            throw new BizException("请求参数不能为空");
        }
        int mode = request.mode() == null ? old.mode() : request.mode();
        if (mode != 1 && mode != 2) {
            throw new BizException("防挂机模式不正确");
        }
        if (request.checkMinutes() == null || request.checkMinutes() < 1 || request.checkMinutes() > 60) {
            throw new BizException("确认在线间隔需在 1~60 分钟之间");
        }
        int seconds = request.confirmSeconds() == null ? old.confirmSeconds() : request.confirmSeconds();
        if (mode == 2 && (seconds < 30 || seconds > 600)) {
            throw new BizException("确认时限需在 30~600 秒之间");
        }
        String prompt = request.promptText() == null ? "" : request.promptText();
        if (prompt.length() > 250) {
            throw new BizException("提示语不能超过 250 字");
        }
        boolean antiIdleEnabled = Boolean.TRUE.equals(request.antiIdleEnabled());
        if (antiIdleEnabled && !StringUtils.hasText(prompt)) {
            throw new BizException("请输入提示语");
        }
        return new KngSettingDtos.CheatConf(
                antiIdleEnabled,
                mode,
                request.checkMinutes(),
                seconds,
                prompt,
                Boolean.TRUE.equals(request.multiCourseEnabled()),
                Boolean.TRUE.equals(request.forbidDragEnabled()),
                Boolean.TRUE.equals(old.audioBackgroundPlay()));
    }

    private String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BizException("设置配置序列化失败");
        }
    }

    private <T> T fromJson(String json, Class<T> type) {
        try {
            return JSON.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BizException("设置配置解析失败");
        }
    }
}
