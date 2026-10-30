package jack.digityhuman.atualcombattraining.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import jack.digityhuman.atualcombattraining.entity.ActStudentScenarioStat;
import jack.digityhuman.atualcombattraining.mapper.ActPracticeAttemptMapper;
import jack.digityhuman.atualcombattraining.mapper.ActStudentScenarioStatMapper;
import jack.digityhuman.atualcombattraining.service.AttemptQuotaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * attempt 配额服务实现
 * <p>
 * 计数口径（避免双扣/漏扣）：
 * <ul>
 *   <li>主题容量按 <b>quota_deducted=1 的 attempt 行数</b> 计，不用累加列——多节点并发下计数天然一致；</li>
 *   <li>单场景/模式次数按 <b>统计表 formal_count</b> 计，与 attempt 行数可能短暂不一致（历史数据），以统计表为准；</li>
 *   <li>考核已用按 attempt 行数（study_mode=2 且已扣次）计，上限叠加管理员追加。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AttemptQuotaServiceImpl implements AttemptQuotaService {

    /** 已扣次标记 */
    private static final int DEDUCTED = 1;
    /** 训练/考核模式值 */
    private static final int MODE_TRAINING = 1;

    private final ActPracticeAttemptMapper attemptMapper;
    private final ActStudentScenarioStatMapper statMapper;

    @Override
    public boolean themeCapacityAvailable(String themeId, int totalDrillCount) {
        if (totalDrillCount <= 0) {
            return true;
        }
        return countDeductedAttempts(themeId) < totalDrillCount;
    }

    @Override
    public boolean sceneQuotaAvailable(String userId, String themeId, String scenarioId,
                                       int studyMode, Integer perSceneLimit) {
        if (perSceneLimit == null || perSceneLimit <= 0) {
            return true;
        }
        return countFormalAttempts(userId, themeId, scenarioId, studyMode) < perSceneLimit;
    }

    @Override
    public boolean examUnlocked(String userId, String themeId, int minTrainCount) {
        if (minTrainCount <= 0) {
            return true;
        }
        return countFormalAttemptsByMode(userId, themeId, MODE_TRAINING) >= minTrainCount;
    }

    @Override
    public boolean examQuotaAvailable(String userId, String themeId, int maxExamCount,
                                      int extraExamAttemptCount) {
        if (maxExamCount <= 0) {
            // 0 = 不限（D19）
            return true;
        }
        long allowed = (long) maxExamCount + Math.max(0, extraExamAttemptCount);
        return countDeductedExamAttempts(userId, themeId) < allowed;
    }

    @Override
    public long countDeductedAttempts(String themeId) {
        return attemptMapper.selectCount(new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getThemeId, themeId)
                .eq(ActPracticeAttempt::getQuotaDeducted, DEDUCTED));
    }

    @Override
    public long countDeductedAttempts(String userId, String themeId, int studyMode) {
        return attemptMapper.selectCount(new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getUserId, userId)
                .eq(ActPracticeAttempt::getThemeId, themeId)
                .eq(ActPracticeAttempt::getStudyMode, studyMode)
                .eq(ActPracticeAttempt::getQuotaDeducted, DEDUCTED));
    }

    @Override
    public long countFormalAttempts(String userId, String themeId, String scenarioId, int studyMode) {
        ActStudentScenarioStat stat = findStat(userId, themeId, scenarioId, studyMode);
        return stat == null || stat.getFormalCount() == null ? 0 : stat.getFormalCount();
    }

    @Override
    public long countFormalAttemptsByMode(String userId, String themeId, int studyMode) {
        return statMapper.selectList(new LambdaQueryWrapper<ActStudentScenarioStat>()
                        .eq(ActStudentScenarioStat::getUserId, userId)
                        .eq(ActStudentScenarioStat::getThemeId, themeId)
                        .eq(ActStudentScenarioStat::getStudyMode, studyMode))
                .stream()
                .mapToLong(stat -> stat.getFormalCount() == null ? 0 : stat.getFormalCount())
                .sum();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int incrementFormalCount(String userId, String themeId, String scenarioId, int studyMode) {
        ActStudentScenarioStat stat = findStat(userId, themeId, scenarioId, studyMode);
        LocalDateTime now = LocalDateTime.now();
        if (stat == null) {
            stat = new ActStudentScenarioStat();
            stat.setId(IdWorker.getIdStr());
            stat.setUserId(userId);
            stat.setThemeId(themeId);
            stat.setScenarioId(scenarioId);
            stat.setStudyMode(studyMode);
            stat.setFormalCount(1);
            stat.setCompletedCount(0);
            stat.setPassedCount(0);
            stat.setConsecutivePassedCount(0);
            stat.setLastAttemptAt(now);
            stat.setUpdatedAt(now);
            statMapper.insert(stat);
            return 1;
        }
        int next = (stat.getFormalCount() == null ? 0 : stat.getFormalCount()) + 1;
        ActStudentScenarioStat update = new ActStudentScenarioStat();
        update.setId(stat.getId());
        update.setFormalCount(next);
        update.setLastAttemptAt(now);
        update.setUpdatedAt(now);
        statMapper.updateById(update);
        return next;
    }

    /** 已用考核次数（已扣次 attempt 行数） */
    private long countDeductedExamAttempts(String userId, String themeId) {
        return attemptMapper.selectCount(new LambdaQueryWrapper<ActPracticeAttempt>()
                .eq(ActPracticeAttempt::getUserId, userId)
                .eq(ActPracticeAttempt::getThemeId, themeId)
                .eq(ActPracticeAttempt::getStudyMode, 2)
                .eq(ActPracticeAttempt::getQuotaDeducted, DEDUCTED));
    }

    private ActStudentScenarioStat findStat(String userId, String themeId, String scenarioId, int studyMode) {
        return statMapper.selectOne(new LambdaQueryWrapper<ActStudentScenarioStat>()
                .eq(ActStudentScenarioStat::getUserId, userId)
                .eq(ActStudentScenarioStat::getThemeId, themeId)
                .eq(ActStudentScenarioStat::getScenarioId, scenarioId)
                .eq(ActStudentScenarioStat::getStudyMode, studyMode)
                .last("LIMIT 1"));
    }
}
