package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.atualcombattraining.common.BizException;
import jack.digityhuman.atualcombattraining.common.PageResult;
import jack.digityhuman.atualcombattraining.dto.req.ThemeListReq;
import jack.digityhuman.atualcombattraining.dto.resp.ScenarioUnitResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeDetailResp;
import jack.digityhuman.atualcombattraining.dto.resp.ThemeListResp;
import jack.digityhuman.atualcombattraining.dto.resp.StatusResp;
import jack.digityhuman.atualcombattraining.entity.ActTheme;
import jack.digityhuman.atualcombattraining.entity.ActThemeVersion;
import jack.digityhuman.atualcombattraining.mapper.ActThemeMapper;
import jack.digityhuman.atualcombattraining.mapper.ActThemeVersionMapper;
import jack.digityhuman.atualcombattraining.service.ScenarioUnitService;
import jack.digityhuman.atualcombattraining.service.ThemeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 训练主题服务实现（FP-06 列表 / FP-07-08 详情 / FP-09 状态机 / 冗余字段同步）
 */
@Service
@RequiredArgsConstructor
public class ThemeServiceImpl implements ThemeService {

    /** 发布状态枚举值 */
    private static final int STATUS_PUBLISHED = 3;
    private static final int STATUS_PAUSED = 4;
    private static final int STATUS_FINISHED = 5;

    private final ActThemeMapper themeMapper;
    private final ActThemeVersionMapper versionMapper;
    private final ScenarioUnitService scenarioUnitService;

    /**
     * FP-06 分页查询主题列表：
     * 1. 组装过滤条件（status/themeMode 精确匹配、keyword 对 theme_name 模糊；
     *    jurisdictionType/sourceType 为占位字段，本次接收但不参与过滤）
     * 2. 按 updated_at 倒序分页查询
     * 3. 实体转列表响应
     */
    @Override
    public PageResult<ThemeListResp> list(ThemeListReq req) {
        LambdaQueryWrapper<ActTheme> wrapper = new LambdaQueryWrapper<>();
        if (req.getStatus() != null) {
            wrapper.eq(ActTheme::getStatus, req.getStatus());
        }
        if (req.getThemeMode() != null) {
            wrapper.eq(ActTheme::getThemeMode, req.getThemeMode());
        }
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            wrapper.like(ActTheme::getThemeName, req.getKeyword().trim());
        }
        wrapper.orderByDesc(ActTheme::getUpdatedAt).orderByDesc(ActTheme::getId);

        Page<ActTheme> page = new Page<>(req.getPage(), req.getPageSize());
        Page<ActTheme> result = themeMapper.selectPage(page, wrapper);

        List<ThemeListResp> records = result.getRecords().stream().map(this::toListResp).toList();
        return new PageResult<>(records, result.getTotal());
    }

    /**
     * FP-07/08 主题详情：
     * 头部信息读主题表冗余字段；stats 读占位统计字段；
     * scenarioUnitCount=当前版本单元实时 count；versionCount=版本总数。
     */
    @Override
    public ThemeDetailResp detail(String id) {
        ActTheme theme = requireTheme(id);

        ThemeDetailResp resp = new ThemeDetailResp();
        resp.setId(theme.getId());
        resp.setThemeName(theme.getThemeName());
        resp.setThemeDesc(theme.getThemeDesc());
        resp.setStatus(theme.getStatus());
        resp.setThemeMode(theme.getThemeMode());
        resp.setAssignMode(theme.getAssignMode());
        resp.setDifficulty(theme.getDifficulty());
        resp.setCurrentVersionNo(String.valueOf(theme.getCurrentVersionNo()));
        resp.setCreatedAt(theme.getCreatedAt());
        resp.setUpdatedAt(theme.getUpdatedAt());

        ThemeDetailResp.Stats stats = new ThemeDetailResp.Stats();
        stats.setPassRate(theme.getPassRate());
        stats.setPassUserCount(theme.getPassUserCount());
        stats.setParticipantCount(theme.getParticipantCount());
        stats.setAvgScore(theme.getAvgScore());
        stats.setTotalTrainingCount(theme.getTotalTrainingCount());
        resp.setStats(stats);

        resp.setScenarioUnitCount((int) scenarioUnitService.countUnits(id, theme.getCurrentVersionNo()));
        Long versionCount = versionMapper.selectCount(new LambdaQueryWrapper<ActThemeVersion>()
                .eq(ActThemeVersion::getThemeId, id));
        resp.setVersionCount(versionCount == null ? 0 : versionCount.intValue());
        return resp;
    }

    /**
     * FP-09 暂停/恢复：已发布(3)→已暂停(4)，已暂停(4)→已发布(3)，其余状态拒绝
     */
    @Override
    public StatusResp pause(String id) {
        ActTheme theme = requireTheme(id);
        int current = theme.getStatus();
        if (current == STATUS_PUBLISHED) {
            theme.setStatus(STATUS_PAUSED);
        } else if (current == STATUS_PAUSED) {
            theme.setStatus(STATUS_PUBLISHED);
        } else {
            throw new BizException("当前状态不允许暂停或恢复");
        }
        touchAndUpdate(theme);
        return buildStatusResp(theme);
    }

    /**
     * FP-09 结束：已发布(3)/已暂停(4)→已结束(5)，终态不可逆；其余状态（含重复结束）拒绝
     */
    @Override
    public StatusResp finish(String id) {
        ActTheme theme = requireTheme(id);
        int current = theme.getStatus();
        if (current == STATUS_PUBLISHED || current == STATUS_PAUSED) {
            theme.setStatus(STATUS_FINISHED);
        } else {
            throw new BizException("当前状态不允许结束");
        }
        touchAndUpdate(theme);
        return buildStatusResp(theme);
    }

    /** FP-11 当前版本场景单元列表（含维度），版本指针取主题表 current_version_no */
    @Override
    public List<ScenarioUnitResp> listScenarioUnits(String id) {
        ActTheme theme = requireTheme(id);
        return scenarioUnitService.listByVersion(id, theme.getCurrentVersionNo());
    }

    /**
     * 同步主题表冗余字段（保存设置与回滚共用）：
     * theme_name/theme_desc/difficulty 取版本值，theme_mode/assign_mode 按版本配置推导，
     * current_version_no 切到目标版本，updated_at 刷新，然后落库。
     */
    @Override
    public void syncRedundantFields(ActTheme theme, ActThemeVersion version) {
        theme.setThemeName(version.getThemeName());
        theme.setThemeDesc(version.getThemeDesc());
        theme.setThemeMode(deriveThemeMode(version.getTrainingModeEnabled(), version.getAssessmentModeEnabled()));
        theme.setAssignMode(version.getJoinMode() != null && version.getJoinMode() == 2 ? 2 : 1);
        theme.setDifficulty(version.getDifficulty());
        theme.setCurrentVersionNo(version.getVersionNo());
        touchAndUpdate(theme);
    }

    /**
     * 主题模式推导：训练开+考核关=1 日常训练；训练关+考核开=2 通关考核；双开=3 训考一体
     */
    static int deriveThemeMode(Integer trainingModeEnabled, Integer assessmentModeEnabled) {
        boolean training = Objects.equals(trainingModeEnabled, 1);
        boolean assessment = Objects.equals(assessmentModeEnabled, 1);
        if (training && !assessment) {
            return 1;
        }
        if (!training && assessment) {
            return 2;
        }
        return 3;
    }

    /** 按主键查询主题，不存在抛业务异常 */
    private ActTheme requireTheme(String id) {
        ActTheme theme = themeMapper.selectById(id);
        if (theme == null) {
            throw new BizException("主题不存在");
        }
        return theme;
    }

    /** 刷新 updated_at 并更新主题行 */
    private void touchAndUpdate(ActTheme theme) {
        theme.setUpdatedAt(LocalDateTime.now());
        themeMapper.updateById(theme);
    }

    private StatusResp buildStatusResp(ActTheme theme) {
        StatusResp resp = new StatusResp();
        resp.setId(theme.getId());
        resp.setStatus(theme.getStatus());
        return resp;
    }

    /** 实体转列表响应 */
    private ThemeListResp toListResp(ActTheme theme) {
        ThemeListResp resp = new ThemeListResp();
        resp.setId(theme.getId());
        resp.setThemeName(theme.getThemeName());
        resp.setThemeDesc(theme.getThemeDesc());
        resp.setStatus(theme.getStatus());
        resp.setThemeMode(theme.getThemeMode());
        resp.setSourceType(theme.getSourceType());
        resp.setParticipantCount(theme.getParticipantCount());
        resp.setUpdatedAt(theme.getUpdatedAt());
        return resp;
    }
}
