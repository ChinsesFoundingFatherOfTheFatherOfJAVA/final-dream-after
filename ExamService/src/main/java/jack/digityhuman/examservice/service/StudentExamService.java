package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.StudentExamDtos;
import jack.digityhuman.examservice.dto.resp.StudentExamRespDtos;

/**
 * 学员端考试只读与入场服务（04 §11.2 / §11.3 / §11.4 / §11.5 / §11.6）。
 *
 * <p>权限口径：所有方法第一个参数都是当前登录用户 id，由 Controller 从 JWT 取；
 * 服务内一律先校验「该用户确实在这场考试的考生名单里」再返回数据，
 * 不依赖前端传什么（04 §11.1）。
 *
 * <p>状态口径：学员状态**不读** {@code exam_arrange_student.exam_status}（那是管理端展示缓存），
 * 一律由 {@code exam_attempt} 实时推算（DER-12），保证列表/入场页/答题页三处一致。
 */
public interface StudentExamService {

    /**
     * §11.2 我的考试列表。
     *
     * @param status 可选过滤：not_started / in_progress / grading / finished / invalid；空=全部
     */
    StudentExamRespDtos.ExamList listMyExams(String userId, String status);

    /** §11.3 入场信息（含 canStart / blockReason / lastResult）。 */
    StudentExamRespDtos.Entry entry(String userId, String arrangeId);

    /** §11.4 考前校验：告知前端要弹哪几个前置步骤（声明 → 签名 → 人脸核验）。 */
    StudentExamRespDtos.PreCheck preCheck(String userId, String arrangeId);

    /** §11.4 考前声明确认（preExamDeclare=1 时才调用），落审计日志后返回。 */
    void declare(String userId, String arrangeId, StudentExamDtos.DeclareReq req);

    /** §11.5 考前/考后签名落库，返回 signId 供 start 绑定答卷。 */
    String sign(String userId, String arrangeId, StudentExamDtos.SignReq req);

    /** §11.6 开始考试（已开考则续答，不新建、不重复计次）。 */
    StudentExamRespDtos.StartResult start(String userId, String arrangeId, StudentExamDtos.StartReq req);

    /**
     * 人脸核验结果落库（契约缺口补充，2026-11-11，DER-07）。
     *
     * <p>只写 `exam_attempt.identity_status` / `identity_evidence`，**不做任何拦截**：
     * DER-07 定的是"核验只采集证据、绝不阻断考试"，所以本方法对任何入参都正常返回。
     *
     * <p>找不到答卷时**静默返回**而不抛错——前端可能在后端建卷前就上报，
     * 把这种时序问题变成学员看到的报错没有意义。
     */
    void reportFaceVerify(String userId, String attemptId, StudentExamDtos.FaceVerifyReq req);
}
