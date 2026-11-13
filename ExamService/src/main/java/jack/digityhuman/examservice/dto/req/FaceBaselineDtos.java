package jack.digityhuman.examservice.dto.req;

import lombok.Data;

/**
 * 人脸基准照相关请求（2026-11-12 新增，DER-33）。
 *
 * <p>为什么要它：动作式活体只能证明「屏幕前是活人」，证明不了「是本人」。
 * 有了基准照之后，后续才能做 1:1 比对，把「换了一个人」检出来。
 *
 * <p>**能力边界（DEF-01）**：基准照由学员自己在登录后拍摄，首次就可以是别人。
 * 因此它只能用于检测「这次和上次不是同一个人」，**不是**身份认证。
 * 这一点必须同时体现在契约和 UI 文案里，不要当成安全机制宣传。
 */
public final class FaceBaselineDtos {

    private FaceBaselineDtos() {
    }

    /** 录入基准照：图片已由前端上传到 `/api/exam/files/**`，这里只收 URL */
    @Data
    public static class SaveReq {
        /** 形如 `/api/exam/files/2026/11/xxx.jpg` */
        private String imageUrl;
    }
}
