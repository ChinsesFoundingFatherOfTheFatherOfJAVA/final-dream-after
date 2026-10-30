package jack.digityhuman.atualcombattraining.service;

/**
 * attempt 配额服务（契约 03 §7.3/§7.4）
 * <p>
 * 两类计数：
 * <ul>
 *   <li>主题总容量：total_drill_count（0=不限），按已扣次 attempt 数计；</li>
 *   <li>单场景次数：per_scene_limit（null/0=不限）+ study_mode 维度统计；</li>
 *   <li>考核另有解锁门槛（min_train_count_to_unlock_exam）与上限（max_exam_count + 管理员追加）。</li>
 * </ul>
 * 只读检查在 preflight 里做；真正的扣减发生在正式开始事务（D06：准备失败不扣）。
 */
public interface AttemptQuotaService {

    /** 主题总容量是否还有余量（0=不限） */
    boolean themeCapacityAvailable(String themeId, int totalDrillCount);

    /** 单场景正式次数是否还有余量（limit null/0=不限） */
    boolean sceneQuotaAvailable(String userId, String themeId, String scenarioId,
                                int studyMode, Integer perSceneLimit);

    /** 考核是否解锁：训练正式次数 >= min_train_count_to_unlock_exam（D19） */
    boolean examUnlocked(String userId, String themeId, int minTrainCount);

    /** 考核次数是否还有余量：已用 < max_exam_count + 追加（max=0 不限） */
    boolean examQuotaAvailable(String userId, String themeId, int maxExamCount,
                               int extraExamAttemptCount);

    /** 主题维度已扣次 attempt 数（容量口径） */
    long countDeductedAttempts(String themeId);

    /** 用户在某主题某模式下的已扣次 attempt 数（考核余量展示用） */
    long countDeductedAttempts(String userId, String themeId, int studyMode);

    /** 单场景某模式的正式次数 */
    long countFormalAttempts(String userId, String themeId, String scenarioId, int studyMode);

    /** 用户在主题下某模式的正式次数合计（考核解锁门槛用） */
    long countFormalAttemptsByMode(String userId, String themeId, int studyMode);

    /** 正式开始时：stat.formal_count +1（无行则建），返回更新后的 formal_count */
    int incrementFormalCount(String userId, String themeId, String scenarioId, int studyMode);
}
