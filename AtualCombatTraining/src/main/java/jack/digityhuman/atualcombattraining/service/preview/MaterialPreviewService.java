package jack.digityhuman.atualcombattraining.service.preview;

import jack.digityhuman.atualcombattraining.entity.ActAiMaterial;

import java.util.Map;

/**
 * 学员端材料预览服务（契约 11）
 * <p>
 * 职责：
 * <ul>
 *   <li>上传后分类：PDF/TXT/MD → not_needed；Office → pending 异步转 PDF；其他 → text_only；</li>
 *   <li>异步转换：previewExecutor 独立线程池（不占训练 runtime / 报告池），DB 乐观锁防并发重复转换；</li>
 *   <li>查看上下文：按发布快照 + 参与关系鉴权，给出 viewerUrl/textUrl/originalUrl 与文案（§6.1）；</li>
 *   <li>发布快照派生：转换成功可更新当前发布快照的 preview 字段（§8，不产生版本升级）。</li>
 * </ul>
 */
public interface MaterialPreviewService {

    /** 材料落库后调用：分类并排队转换（异步，失败不影响上传/解析/发布） */
    void classifyAndEnqueue(ActAiMaterial material);

    /** 同步转换一次（供 previewExecutor 任务与测试）：成功置 ready，失败置 failed */
    boolean convertNow(String materialId);

    /**
     * 查看上下文（§6.1）：学员视角的字段与 URL。
     * material 必须属于该学员参与主题的当前发布快照；在线课堂材料不带 viewerUrl（走在线课堂 viewer）。
     */
    Map<String, Object> viewerContext(String userId, String materialId);

    /** 状态常量 */
    String STATUS_NOT_NEEDED = "not_needed";
    String STATUS_PENDING = "pending";
    String STATUS_READY = "ready";
    String STATUS_TEXT_ONLY = "text_only";
    String STATUS_FAILED = "failed";
}
