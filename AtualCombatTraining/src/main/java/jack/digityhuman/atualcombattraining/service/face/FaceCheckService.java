package jack.digityhuman.atualcombattraining.service.face;

import java.util.Map;

/**
 * 人脸核验服务（契约 03 §12.2/§12.3）
 * <p>
 * 职责：抓拍 → provider 比对 → 证据落库 → 返回判定结果（不返回底层异常、不返回 provider 密钥）。
 * 启用规则：主题设置启用人脸但 provider 未配置时，**开始前二次校验拒绝**（避免假防作弊）；
 * 训练模式不受影响（纯训练不做人脸）。
 */
public interface FaceCheckService {

    /** provider 是否已配置（前端据此决定是否请求摄像头；未配置时主题不允许启用） */
    boolean providerConfigured();

    /**
     * 校验一张抓拍并存证据。
     *
     * @param attemptId attempt
     * @param userId    学员（归属校验）
     * @param stage     precheck/ongoing
     * @param jpeg      抓拍 JPEG
     * @return {passed, score, reason, evidenceId}
     */
    Map<String, Object> verify(String attemptId, String userId, String stage, byte[] jpeg);
}
