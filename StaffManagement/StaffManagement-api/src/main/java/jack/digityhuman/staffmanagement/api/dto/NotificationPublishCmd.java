package jack.digityhuman.staffmanagement.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 通知发布入参（08 §4 NotificationPublishCmd）。
 *
 * <p>字段全部用 String / List&lt;String&gt; 而非日期或枚举类型，是为了让 api 模块保持
 * "零运行时依赖"（AGENTS 规定 api 只放 POJO 契约），也避免消费方与提供方的时间格式化差异。
 */
@Data
public class NotificationPublishCmd implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 来源服务：exam / course / training ... */
    private String sourceService;

    /** 事件码：ARRANGE_CHANGE / ARRANGE_CREATED / ARRANGE_CANCELED ... */
    private String eventCode;

    /** 业务类型：ARRANGE / COURSE / TASK */
    private String bizType;

    /** 业务主键，如 arrangeId */
    private String bizId;

    /** 幂等键，必填且唯一；推荐 {@code <source>:<eventCode>:<bizId>:<batch>} */
    private String dedupKey;

    private String title;

    private String content;

    /** 事件参数快照（JSON 字符串），仅留痕，不参与渲染 */
    private String payloadJson;

    /** 0 普通 1 重要 2 紧急 */
    private Integer severity;

    /** 渠道，逗号分隔；首期只实现 IN_APP，其余写失败原因留痕 */
    private String channels;

    /** 计划发布时间 yyyy-MM-dd HH:mm:ss，空表示立即 */
    private String publishAt;

    /** 过期时间 yyyy-MM-dd HH:mm:ss，空表示不过期 */
    private String expireAt;

    /** 直接收件人（首期唯一入口）；保留顺序、内部去重 */
    private List<String> recipientUserIds;

    /** 收件人规则 JSON（预留：DEPT_CODES / ROLE_CODES / ALL），首期不使用 */
    private String recipientRuleJson;

    /** 事件创建者标识（如触发变更的考试管理员 userId），仅留痕 */
    private String createdBy;
}
