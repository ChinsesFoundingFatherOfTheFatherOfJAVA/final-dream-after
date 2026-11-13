package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.FaceBaselineDtos;

/**
 * 学员人脸基准照（2026-11-12，DER-33）。
 *
 * <p>**为什么独立于 `StudentExamService`**：基准照是「学员级」资料，跨考试存在；
 * 而 exam 那份服务围绕「某一场考试的入场流程」。混在一起会让依赖方向变乱
 * （入场流程反而不需要知道基准照怎么维护）。
 */
public interface FaceBaselineService {

    /**
     * 查当前学员是否已有基准照。
     *
     * @return 已有的图片 URL；没有则返回 null（前端据此决定是否提示录入）
     */
    String currentImageUrl(String userId);

    /**
     * 录入/覆盖基准照。一个学员只保留一张（`uk_user`），重录即覆盖。
     *
     * @return 保存后的图片 URL
     */
    String save(String userId, FaceBaselineDtos.SaveReq req);
}
