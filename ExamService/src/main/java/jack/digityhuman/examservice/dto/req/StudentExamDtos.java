package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.math.BigDecimal;

/** 学员端考试请求容器（04 §11.4 / §11.5 / §11.6）。 */
public final class StudentExamDtos {

    private StudentExamDtos() {
    }

    /**
     * §11.4 考前声明确认。
     *
     * <p>只有 `preExamDeclare=1` 的考试前端才会调本接口；未勾选（accepted=false）时
     * 由后端拒绝并返回 40052，不能只靠前端禁用按钮（前端置灰可被绕过）。
     */
    @Data
    public static class DeclareReq {
        private Boolean accepted;
    }

    /** §11.5 考前/考后签名。 */
    @Data
    public static class SignReq {
        /** 前端上传到 /exam/files/** 后得到的 URL */
        private String imageUrl;
        /** pre 考前 / post 考后 */
        private String stage = "pre";
    }

    /**
     * §11.6 开始考试。
     *
     * <p>两个字段都是"前置步骤已完成"的凭证，后端按配置校验：
     * `declareAccepted=true` 才能过声明闸门，`signId` 才能过签名闸门。
     */
    @Data
    public static class StartReq {
        /** §11.5 返回的 signId，答卷创建时绑定到 exam_arrange_signature.attempt_id */
        private String signId;
        private Boolean declareAccepted;
    }

    /**
     * 人脸核验结果落库（契约缺口补充，2026-11-11，DER-07）。
     *
     * <p>为什么不塞进 {@link StartReq}：核验是**独立的一次性证据采集**，
     * 且 DER-07 定的是"无论成败都放行"，与 start 的闸门语义相反。
     * 混进 start 会让后来人误以为核验不通过会挡住开考。
     *
     * <p>本接口**永不返回业务失败给前端阻断流程**：参数非法也只记录不拦。
     */
    @Data
    public static class FaceVerifyReq {
        /**
         * 0 未核验 / 1 通过 / 2 不通过 / 3 无法核验。
         * 前端传 pass 或 fail 或 error，由后端映射成 1/2/3。
         */
        private Integer identityStatus;
        /** 抓拍图 URL（复用 /exam/files/** 存储）；可为空，为空时保留原值 */
        private String identityEvidence;
        /**
         * 核验手段（2026-11-13，DER-34）：**1 仅活体 / 2 活体 + 基准照比对**。
         *
         * <p>缺省按 1（仅活体）记：前端不传时不能凭空声称“比对过了”。
         */
        private Integer identityMethod;
        /**
         * 人脸比对相似度 0..1（DER-34）；未做比对时为空。
         *
         * <p>**前端计算结果，后端不重算**——方案 A 的既定取舍：
         * 事后防不住能改前端的人，该风险已计入契约（DER-34），不在这里假装能防。
         */
        private BigDecimal identityScore;
    }
}
