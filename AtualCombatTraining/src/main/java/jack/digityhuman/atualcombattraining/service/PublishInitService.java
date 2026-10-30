package jack.digityhuman.atualcombattraining.service;

import jack.digityhuman.atualcombattraining.dto.resp.PublishResp;
import jack.digityhuman.atualcombattraining.dto.resp.ScriptProgressResp;

/**
 * 发布与剧本初始化服务（FP-41，2026-10-30 版本语义重构）
 * <p>
 * 发布 = 首次发布把隐藏 v0 工作内容固化为公开 v1 + 状态置"发布中" + 异步逐案例编译剧本
 * （纯 Java 组装，无 LLM 调用，发布不产生 AI 费用）。
 * 全部案例编译成功 → 主题置"已发布"。
 * 部分失败 → 保持"发布中"，进度接口暴露 failed 数，前端可调用 retry 仅重试失败项。
 * <p>
 * 已发布主题的"再发布"走 AI 确认路径（{@link ContentVersionService} + 本服务 compileNewVersionAsync），
 * 不再经过 publish 按钮（FP-43：已发布主题头部没有发布按钮）。
 */
public interface PublishInitService {

    /**
     * 首次发布主题（仅草稿；校验：有已确认内容、至少 1 个案例）。
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

    /**
     * 为指定内容版本异步编译全部待编译案例（AI 确认升版路径专用）。
     * 与 publish 的差异：不改主题状态（已发布主题保持已发布），不切换任何指针。
     *
     * @param themeId   主题 ID
     * @param versionNo 刚创建并已写入内容的新版本号
     */
    void compileNewVersionAsync(String themeId, int versionNo);
}
