package jack.digityhuman.examservice.dto.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 考试管理响应容器（04 §1.2/1.3）。 */
public final class ArrangeRespDtos {

    private ArrangeRespDtos() {
    }

    @Data
    public static class Permissions {
        private boolean canEdit = true;
        private boolean canDelete = true;
        private boolean canPublish = true;
        private boolean canEnd = true;
    }

    /** API-08 列表行。 */
    @Data
    public static class ArrangeItem {
        private String id;
        private String name;
        private String catalogId;
        private String catalogName;
        private Integer examType;
        /** 0 未发布 2 进行中 3 已结束 */
        private Integer status;
        private Integer auditStatus;
        private Integer arrangeType;
        private Integer offline;
        private String paperId;
        private Integer paperBuildType;
        private String entryStartAt;
        private String entryEndAt;
        private String createUserId;
        private String createFullname;
        private String createUserName;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private String createdAt;
        private List<String> ownerUserIds;
        private List<String> ownerNames;
        private Integer arrangedQty;
        private Integer submittedQty;
        private Integer passedQty;
        private BigDecimal passRate;
        private Permissions permissions;
    }

    /** API-09 详情 = 列表行 + 发布表单全字段。 */
    @Data
    public static class ArrangeDetail extends ArrangeItem {
        private String code;
        private Integer important;
        private Integer secrecy;
        private Integer loopEnabled;
        private Integer requiredType;
        private Integer passScoreType;
        private BigDecimal passScore;
        private BigDecimal passRatio;
        private Integer rewardType;
        private BigDecimal rewardCredit;
        private Integer attemptLimit;
        private Integer lateLimitMin;
        private Integer screenSwitchLimit;
        private Integer stepNumber;
        private String paperName;
        private BigDecimal paperScore;
        private String linkUrl;
        private String qrCodeUrl;
        private Map<String, Object> notifySettings;
        private Map<String, Object> publishSettings;
        private Map<String, Object> advancedSettings;
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private String publishedAt;
    }

    /** API-17 考试链接。 */
    @Data
    public static class LinkResp {
        private String token;
        private String linkUrl;
        /** 二维码内容（前端 qrcode.vue 直接编码该串） */
        private String qrCodeUrl;
        private Integer joinQty;
    }
}