package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.util.List;

/** AI 建题响应容器（03 §7.1）。 */
public final class AiGenRespDtos {

    private AiGenRespDtos() {
    }

    @Data
    public static class AiGenResult {
        private List<QuestionDraft> questions;
    }

    /**
     * 题目草稿：与 Dify 工作流的 structured_output 同构，前端卡片流直接可编辑。
     * 题型 type：0单选 1多选 2判断 3填空 4问答（不生成组合题 5）。
     */
    @Data
    public static class QuestionDraft {
        private Integer type;
        /** 0易 1中 2难。 */
        private Integer levelType;
        private String stem;
        /** 单/多选为选项文本；判断固定两项；填空/问答为空。 */
        private List<String> options;
        /** 单选 "A" / 多选 "ACD" / 判断 "正确" / 填空按空换行 / 问答参考答案文本。 */
        private String answer;
        /** 问答题关键词及占比。 */
        private List<Keyword> keywords;
        private String analysis;
    }

    @Data
    public static class Keyword {
        private String keyword;
        private Integer scoreRatio;
    }
}