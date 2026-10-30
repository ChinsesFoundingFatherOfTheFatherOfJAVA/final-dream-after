package jack.digityhuman.atualcombattraining.service.face;

import java.util.Map;

/**
 * 人脸核验 provider 适配器（契约 03 §12.2）
 * <p>
 * 契约要求"配置化 HTTP 适配器"：provider 只由配置决定，不在运行时探测或调用任意外部服务；
 * 未配置时不提供能力（{@link #configured()} false），主题设置因此不允许启用人脸校验。
 * <p>
 * provider key 只存在服务端环境变量，永不下放前端（§12.2 最后一条）。
 * 抓拍证据写 act_face_evidence（storage_path + provider_result_json + passed），
 * 只允许管理员通过学员详情查看判定结果，不默认展示完整照片（§12.3）。
 */
public interface FaceVerificationProvider {

    /** provider 标识（none=http-verify 等），仅日志与审计用 */
    String name();

    /** 是否已配置（未配置时主题不允许启用脸校验，运行前二次校验也据此拒绝） */
    boolean configured();

    /**
     * 比对一张抓拍。
     *
     * @param jpegBytes 抓拍 JPEG（前端压缩后上传，不传原始视频流）
     * @param stage     precheck（开始前）/ ongoing（进行中）
     * @return {passed: boolean, score: double, reason: String}；provider 异常时 passed=false 且 reason 可读
     */
    Map<String, Object> verify(byte[] jpegBytes, String stage);
}
