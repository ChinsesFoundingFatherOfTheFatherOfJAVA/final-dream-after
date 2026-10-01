package jack.digityhuman.speechtraining.dify;

import jack.digityhuman.speechtraining.dto.resp.AiScoringResp;

/**
 * Dify 评分智能体调用：评估学员的回答。
 */
public interface DifyScoringService {

    /**
     * 调用 Dify 评分智能体，评估学员的回答。
     *
     * @param standardScript 标准话术
     * @param keyPoints      关键要点
     * @param passCondition  通过条件
     * @param userInput      学员回答
     * @return 评分结果（命中要点 / 计数 / 评语 / 是否通过）
     */
    AiScoringResp score(String standardScript, String keyPoints, String passCondition, String userInput);
}
