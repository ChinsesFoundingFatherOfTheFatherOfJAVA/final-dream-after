package jack.digityhuman.examservice.dto.resp;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 答卷详情响应容器（04 §7 API-39）。 */
public final class ExamResultRespDtos {

    private ExamResultRespDtos() {
    }

    @Data
    public static class ResultView {
        private String ueId;
        private String arrId;
        private String arrName;
        private String examName;
        private BigDecimal totalScore;
        private BigDecimal passScore;
        private Integer usedTime;
        private BigDecimal score;
        private Integer status;
        private String submitAt;
        private List<String> markerUsers;
        private String fullname;
        private String userName;
        private String userNo;
        private String deptName;
        private String positionName;
        private List<TypeTitle> quesTypeTitles;
        private List<UserQues> userQues;
    }

    @Data
    public static class TypeTitle {
        private Integer type;
        private Integer orderIndex;
        private String alias;
        private String description;
    }

    @Data
    public static class UserQues {
        private String id;
        private String content;
        private Integer quesType;
        private Integer orderIndex;
        private String pointNames;
        private String submitContent;
        private String answerContent;
        private String explainText;
        private BigDecimal totalScore;
        private BigDecimal score;
        /** 0 错 1 对 2 部分对 3 待批阅 */
        private Integer checked;
        private List<ChoiceItem> choiceItems;
        private List<String> fillInItems;
    }

    @Data
    public static class ChoiceItem {
        private String itemContent;
        /** 考生是否选了该项 */
        private Boolean checked;
        /** 是否正确选项 */
        private Integer answer;
    }
}