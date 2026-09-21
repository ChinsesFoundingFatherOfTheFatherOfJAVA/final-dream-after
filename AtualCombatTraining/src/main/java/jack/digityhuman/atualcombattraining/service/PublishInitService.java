package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.PublishResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScriptProgressResp;

/**
 * 发布与剧本初始化服务（FP-41）
 * <p>
 * 发布 = 状态置"发布中" + 异步逐案例编译剧本（纯 Java 组装，无 LLM 调用，发布不产生 AI 费用）。
 * 全部案例编译成功 → 主题置"已发布" + 新建业务版本（AI 路径版本在发布时产生）+ 冗余同步。
 * 部分失败 → 保持"发布中"，进度接口暴露 failed 数，前端可调用 retry 仅重试失败项。
 */
public interface PublishInitService {

    /**
     * 发布主题（校验：有已确认内容、状态 ∈ {草稿, 已发布}）。
     *
     * @param themeId 主题 ID
     * @return {status, totalCases}
     */
    PublishResp publish(String themeId);

    /**
     * 初始化进度（前端 5s 轮询）。
     *
     * @param themeId 主题 ID
     * @return {total, completed, failed, progress, status}
     */
    ScriptProgressResp scriptProgress(String themeId);

    /**
     * 仅重试失败项（编译失败的案例重新排队）。
     *
     * @param themeId 主题 ID
     */
    void retry(String themeId);
}