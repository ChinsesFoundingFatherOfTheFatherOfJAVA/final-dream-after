package jack.digityhuman.examservice.service;

import jack.digityhuman.examservice.dto.req.StudentAnswerDtos;
import jack.digityhuman.examservice.dto.resp.StudentAnswerRespDtos;

/**
 * 学员端答题服务（04 §11.7–§11.11，03 §10B.5）。
 *
 * <p>鉴权口径：每个方法都接收当前用户 id，服务内先校验「这张答卷属于该用户」再动作。
 * 不看前端传的 arrangeId——{@code attemptId} 是唯一入口，避免造一个"别人的答卷 id + 自己的考试 id"的组合。
 *
 * <p>三条贯穿全域的约束：
 * <ol>
 *   <li><b>答案存选项 id</b>（不是字母）：{@code answer_json = {"answer":[...],"attach":[]}}；</li>
 *   <li><b>逐题 upsert</b>：唯一键 {@code uk_attempt_ques}，重复提交覆盖不叠加；</li>
 *   <li><b>已交卷只读</b>：写接口一律 40053，交卷是终态（01 §S5）。</li>
 * </ol>
 */
public interface StudentAnswerService {

    /** §11.7 取题目（答题页）。不返回任何标准答案/解析字段。 */
    StudentAnswerRespDtos.Paper paper(String userId, String attemptId);

    /** §11.8 逐题保存（幂等 upsert），返回实际写入条数。 */
    int saveAnswers(String userId, String attemptId, StudentAnswerDtos.SaveAnswersReq req);

    /** §11.9 题目标记，返回落库后的标记值。 */
    int flag(String userId, String attemptId, StudentAnswerDtos.FlagReq req);

    /** §11.10 心跳与时间校正（同时触发超时/切屏超限的强制判定）。 */
    StudentAnswerRespDtos.TimeState timeState(String userId, String attemptId);

    /** §11.10 切屏等异常事件上报；超 `screenSwitchLimit` 时返回 true 表示已强制交卷。 */
    boolean reportAntiCheat(String userId, String attemptId, StudentAnswerDtos.AntiCheatReq req);

    /** §11.11 交卷（幂等）；返回客观题分与待批阅分（DER-03）。 */
    StudentAnswerRespDtos.SubmitResult submit(String userId, String attemptId, StudentAnswerDtos.SubmitReq req);
}
