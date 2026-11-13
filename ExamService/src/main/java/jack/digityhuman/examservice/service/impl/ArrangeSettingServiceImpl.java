package jack.digityhuman.examservice.service.impl;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import jack.digityhuman.common.api.BizException;
import jack.digityhuman.examservice.common.CurrentUserService;
import jack.digityhuman.examservice.dto.req.ArrangeSettingDtos;
import jack.digityhuman.examservice.entity.ExamUserSetting;
import jack.digityhuman.examservice.mapper.ExamUserSettingMapper;
import jack.digityhuman.examservice.service.ArrangeSettingService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 考生列设置实现（API-46/47）。
 *
 * <p>规则（04 §9）：account / fullname / operation 三列必须可见——隐藏请求会被后端拒绝，
 * 不能只靠前端置灰；order 一律重新从 1 递增，避免前端传重复值。
 */
@Service
@RequiredArgsConstructor
public class ArrangeSettingServiceImpl implements ArrangeSettingService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BIZ = "arrange_student_columns";
    /** 保留列：不可隐藏（04 §9 规则） */
    private static final List<String> LOCKED = List.of("account", "fullname", "operation");
    /** 默认列顺序：与原平台「列设置」面板一致（26 项，保留列在操作列尾部）。 */
    private static final List<String> DEFAULT_ORDER = List.of(
            "account", "fullname", "accountStatus", "deptName", "positionName",
            "joinType", "joinSource", "examStatus", "startAt", "submitAt",
            "firstJoinAt", "ip", "durationSec", "score", "highestAttemptNo",
            "screenSwitchCount", "submitType", "rankNo", "passStatus", "retakeScore",
            "retakePassStatus", "referDevice", "referCount", "oncePassed", "hasRetake",
            "operation");

    private final ExamUserSettingMapper settingMapper;
    private final CurrentUserService currentUserService;

    @Override
    public ArrangeSettingDtos.ColumnSetting getStudentColumns() {
        String uid = currentUserService.userId(null);
        ExamUserSetting row = settingMapper.selectOne(Wrappers.<ExamUserSetting>lambdaQuery()
                .eq(ExamUserSetting::getUserId, uid)
                .eq(ExamUserSetting::getBizType, BIZ)
                .last("limit 1"));
        if (row == null || !StringUtils.hasText(row.getSettingJson())) {
            return defaultSetting();
        }
        try {
            return JSON.readValue(row.getSettingJson(), ArrangeSettingDtos.ColumnSetting.class);
        } catch (Exception e) {
            return defaultSetting();
        }
    }

    @Override
    @Transactional
    public void saveStudentColumns(ArrangeSettingDtos.ColumnSetting req) {
        if (req == null || req.getColumns() == null || req.getColumns().isEmpty()) {
            throw new BizException("列设置不能为空");
        }
        Map<String, ArrangeSettingDtos.Column> map = new LinkedHashMap<>();
        for (ArrangeSettingDtos.Column c : req.getColumns()) {
            if (c == null || !StringUtils.hasText(c.getKey())) {
                continue;
            }
            map.put(c.getKey(), c);
        }
        for (String locked : LOCKED) {
            ArrangeSettingDtos.Column c = map.get(locked);
            if (c == null) {
                c = new ArrangeSettingDtos.Column();
                c.setKey(locked);
                map.put(locked, c);
            }
            if (Boolean.FALSE.equals(c.getVisible())) {
                throw new BizException("账号 / 姓名 / 操作列不可隐藏");
            }
            c.setVisible(true);
        }
        List<ArrangeSettingDtos.Column> ordered = new ArrayList<>(map.values());
        ordered.sort((a, b) -> Integer.compare(
                a.getOrder() == null ? Integer.MAX_VALUE : a.getOrder(),
                b.getOrder() == null ? Integer.MAX_VALUE : b.getOrder()));
        int i = 1;
        for (ArrangeSettingDtos.Column c : ordered) {
            c.setOrder(i++);
        }
        ArrangeSettingDtos.ColumnSetting setting = new ArrangeSettingDtos.ColumnSetting();
        setting.setColumns(ordered);

        String uid = currentUserService.userId(null);
        ExamUserSetting row = settingMapper.selectOne(Wrappers.<ExamUserSetting>lambdaQuery()
                .eq(ExamUserSetting::getUserId, uid)
                .eq(ExamUserSetting::getBizType, BIZ)
                .last("limit 1"));
        try {
            String json = JSON.writeValueAsString(setting);
            if (row == null) {
                row = new ExamUserSetting();
                row.setId(IdWorker.getIdStr());
                row.setUserId(uid);
                row.setBizType(BIZ);
                row.setSettingJson(json);
                row.setCreatedAt(LocalDateTime.now());
                row.setUpdatedAt(LocalDateTime.now());
                settingMapper.insert(row);
            } else {
                row.setSettingJson(json);
                row.setUpdatedAt(LocalDateTime.now());
                settingMapper.updateById(row);
            }
        } catch (Exception e) {
            throw new BizException("保存列设置失败");
        }
    }

    private ArrangeSettingDtos.ColumnSetting defaultSetting() {
        ArrangeSettingDtos.ColumnSetting s = new ArrangeSettingDtos.ColumnSetting();
        List<ArrangeSettingDtos.Column> cols = new ArrayList<>();
        int i = 1;
        for (String key : DEFAULT_ORDER) {
            ArrangeSettingDtos.Column c = new ArrangeSettingDtos.Column();
            c.setKey(key);
            c.setVisible(true);
            c.setOrder(i++);
            cols.add(c);
        }
        s.setColumns(cols);
        return s;
    }
}