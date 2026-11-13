package jack.digityhuman.examservice.service;

/**
 * 判分调度（03 §10B.6 / 04 §11C）。
 *
 * <p><b>为什么判分要独立于答题服务</b>：交卷接口只负责"把客观题分先给出来"（DER-03），
 * 而完整判分要不要判主观题取决于考试配置（`grading.autoGradeFill/Qa`），
 * 且未来要换成异步任务。放在答题服务里会让"交卷"这个用户动作承担后台职责。
 *
 * <p>本实现是**同步**的：项目当前没有线程池/`@Async` 基础设施（全仓 grep 零命中），
 * 引入异步需要先加执行器配置，超出本任务范围。契约要求"交卷后立即返回、判分异步执行"，
 * 在同步实现下表现为"交卷请求内完成判分"——对学员是更快的反馈，不改变任何结果口径。
 * 后续接入异步只需把 {@link #grade(String)} 的调用点挪进投递器，本接口不用改。
 */
public interface ExamGradingService {

    /**
     * 判一张答卷：客观题必判，主观题按配置分流；全部判完后收口 status=3。
     *
     * <p>幂等：已判的题（`grade_mode != 0`）跳过，重跑不会覆盖人工批阅结果。
     *
     * @param attemptId 答卷 id
     * @return 判分结果摘要，供调用方判断是否需要提示"仍有待批阅"
     */
    GradingOutcome grade(String attemptId);

    /**
     * 判分结果摘要。
     *
     * @param gradedAll     是否全部题都已判完（决定 status 是否收口到 3）
     * @param objectiveScore 客观题得分
     * @param objectiveFull  客观题满分
     * @param pendingQty    仍需人工批阅的题数
     */
    record GradingOutcome(boolean gradedAll, java.math.BigDecimal objectiveScore,
                          java.math.BigDecimal objectiveFull, int pendingQty) {
    }
}
