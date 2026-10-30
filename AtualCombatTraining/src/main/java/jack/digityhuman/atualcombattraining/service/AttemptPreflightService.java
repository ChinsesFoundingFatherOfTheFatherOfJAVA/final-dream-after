package jack.digityhuman.atualcombattraining.service;

/**
 * attempt 预检服务（契约 03 §7.2：preflight 只读检查，不写 attempt）
 * <p>
 * 检查项与拒绝理由（D02/D13/D18/D19/D22）：
 * 主题已发布、计划时间窗（硬准入）、参与关系有效、练习方式允许、模式开关、
 * 考核解锁与上限、单场景次数、主题容量、无进行中 attempt。
 * <p>
 * createAttempt 会<b>再跑一遍</b>同样的检查——preflight 与 create 之间状态可能变化。
 */
public interface AttemptPreflightService {

    /**
     * 执行全部只读检查。
     *
     * @throws jack.digityhuman.common.api.BizException 任一检查不通过，message 为可直接展示的拒绝理由
     */
    void check(String userId, String themeId, String scenarioId, int studyMode, String practiceType);

    /**
     * 同上，但排除指定 attempt 的 active_key——正式开始时重跑预检用：
     * attempt 自己已占 active_key，不能被当成"另一场进行中的练习"（否则 start 永远失败）。
     */
    void checkExcludingSelf(String userId, String themeId, String scenarioId,
                            int studyMode, String practiceType, String attemptId);

    /**
     * 预检详情（契约 04 API-A01）：不抛异常，把每个检查的通过情况、余量、
     * 自定义向导步骤与 maxDuration 一并返回，供前端渲染（预检是"解释性"接口，不写数据）。
     */
    jack.digityhuman.atualcombattraining.dto.resp.PreflightResp inspect(
            String userId, String themeId, String scenarioId, int studyMode, String practiceType);
}
