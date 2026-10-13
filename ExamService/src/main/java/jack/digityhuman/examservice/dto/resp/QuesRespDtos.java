package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.util.List;

/** 试题模块响应容器（03 §3）。 */
public final class QuesRespDtos {

    private QuesRespDtos() {
    }

    /** 3.1 列表行。 */
    @Data
    public static class QuesItem {
        private String id;
        private String summary;
        private Integer type;
        private Integer levelType;
        private Integer disabled;
        private Integer duplicate;
        /** 0 纯文本；1 题干/选项带媒体（列表给图标用）。 */
        private Integer isMultiMediaType;
        /** 主考核点 = 关联表中 order_index 最小的那个。 */
        private String mainPointName;
        /** 全部考核点名，分号拼接。 */
        private String pointNames;
        private String createFullname;
        private String createUserName;
        private String createTime;
        private String updateFullname;
        private String updateTime;
        private Integer subQuesQty;
    }

    /** 3.2 详情（编辑页 / 查看抽屉共用）。 */
    @Data
    public static class QuesDetail {
        private String id;
        private String libId;
        private String libName;
        private String parentId;
        private Integer type;
        private Integer levelType;
        private String content;
        private String summary;
        private Integer disabled;
        private Integer duplicate;
        private JsonNode stemPlay;
        private String explainText;
        private JsonNode explainPlay;
        private List<ItemResp> questionItems;
        private Integer judgeAnswer;
        private String judgeCorrectOptionContent;
        private String judgeWrongOptionContent;
        private String answerContent;
        private JsonNode answerPlay;
        private List<Keyword> answerKeywords;
        private Integer noOrderMark;
        private List<PointResp> points;
        /** 组合题子题（仅 type=5 有值；复用同一结构，子题不含 points/libName）。 */
        private List<QuesDetail> subQuestions;
    }

    @Data
    public static class ItemResp {
        private String id;
        private String itemContent;
        private Integer answer;
        private Integer orderIndex;
        private JsonNode itemPlay;
    }

    @Data
    public static class Keyword {
        private String keyword;
        private Integer scoreRatio;
    }

    @Data
    public static class PointResp {
        private String pointId;
        private String name;
        private Integer orderIndex;
    }
    /**
     * 导入结果（03 §6.2）：逐行 try-catch，错误行不影响其它行入库。
     */
    @Data
    public static class ImportResult {
        private int successCount;
        private int failCount;
        private List<ImportError> errors = new java.util.ArrayList<>();

        @Data
        public static class ImportError {
            /** 行号（Excel 取实际行号；Word 取题序号） */
            private int line;
            private String reason;

            public ImportError(int line, String reason) {
                this.line = line;
                this.reason = reason;
            }
        }
    }
}