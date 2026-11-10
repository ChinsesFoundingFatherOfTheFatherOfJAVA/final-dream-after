package jack.digityhuman.examservice.dto.req;

import lombok.Data;

import java.util.List;

/** 试题模块请求容器（03 §3）。 */
public final class QuesDtos {

    private QuesDtos() {
    }

    /** 3.1 列表查询：分页/排序在 query，筛选在 body。 */
    @Data
    public static class ListQuery {
        private String libId;
        private String keyword;
        /** -1 全部；0单 1多 2判断 3填空 4问答 5组合。 */
        private Integer type = -1;
        /** -1 全部。 */
        private Integer levelType = -1;
        /** -1 全部。 */
        private Integer disabled = -1;
        private String pointId;
        /** 07 §9 增量：按试题 id 精确过滤（试卷管理页跳转定位单题用）。 */
        private String quesId;
        /** 1 时只返回查重命中的重复题。 */
        private Integer isDuplicate = 0;
    }

    /** 3.3/3.4 新建与编辑同构（无 id/审计字段）。 */
    @Data
    public static class SaveReq {
        private String libId;
        private Integer type;
        private Integer levelType;
        /** 题干 HTML。 */
        private String content;
        /** 纯文本题干（列表展示 + 查重归一）。 */
        private String summary;
        private String stemPlay;
        private String explainText;
        private String explainPlay;
        private List<ItemReq> questionItems;
        /** 判断题：0 正确 1 错误。 */
        private Integer judgeAnswer;
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        /** 填空按行对应 _(n)_；问答为参考答案。 */
        private String answerContent;
        private String answerPlay;
        private List<Keyword> answerKeywords;
        private Integer noOrderMark;
        /** 填空题「按空得分」开关：1=开启，每空可自定义分值（具体每空分值在组卷侧配）。 */
        private Integer blankScoreMark;
        /** 考核点（有序；第一个为主考核点）。 */
        private List<PointRef> points;
        /** 组合题（type=5）子题；子题本身不再嵌套子题，故用扁平的 SubQuesReq。 */
        private List<SubQuesReq> subQuestions;
    }

    /**
     * 组合题子题：与 {@link SaveReq} 同构，但去掉 libId/points/subQuestions——
     * 子题继承父题的 lib_id，考核点由父题统一管（03 §3.2 subQuestions）。
     */
    @Data
    public static class SubQuesReq {
        private Integer type;
        private Integer levelType;
        private String content;
        private String summary;
        private String stemPlay;
        private String explainText;
        private String explainPlay;
        private List<ItemReq> questionItems;
        private Integer judgeAnswer;
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        private String answerContent;
        private String answerPlay;
        private List<Keyword> answerKeywords;
        private Integer noOrderMark;
        /** 填空题「按空得分」开关：1=开启，每空可自定义分值（具体每空分值在组卷侧配）。 */
        private Integer blankScoreMark;
    }

    @Data
    public static class ItemReq {
        private String id;
        private String itemContent;
        /** 1 为正确选项。 */
        private Integer answer;
        private Integer orderIndex;
        private String itemPlay;
    }

    @Data
    public static class Keyword {
        private String keyword;
        private Integer scoreRatio;
    }

    @Data
    public static class PointRef {
        private String pointId;
        private String name;
        private Integer orderIndex;
    }

    /** 3.6 批量（delete|disable|changeLib|setPoint）。 */
    @Data
    public static class BatchReq {
        private String action;
        private List<String> quesIds;
        /** changeLib 用：目标题库。 */
        private String libId;
        private String pointId;
        /** setPoint 用：覆盖式设置考核点（有序，第一个为主考核点）。 */
        private List<String> pointIds;
        /** disable 用：1 禁用 / 0 启用。 */
        private Integer disabled;
    }
    /** 3.8 查重请求：对某题库的某题型执行查重。 */
    @Data
    public static class DuplicateCheckReq {
        private String libId;
        /** 0单选 1多选 2判断 3填空 4问答（组合题不参与查重）。 */
        private Integer type;
    }
}