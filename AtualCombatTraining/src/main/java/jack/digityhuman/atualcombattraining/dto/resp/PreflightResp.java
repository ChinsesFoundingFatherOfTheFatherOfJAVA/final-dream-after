package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 练习预检响应（契约 04 API-A01）
 * <p>
 * 不只返回 allowed：前端据此渲染自定义向导的 requiredSteps、
 * 展示剩余次数与 maxDuration，并按 checks 解释阻断原因（第一个失败即阻断）。
 */
@Data
public class PreflightResp {

    private boolean allowed;
    /** 已有的活动 attempt（有值时前端可直接续练而非新建） */
    private String activeAttemptId;
    /** 自定义练习需要的步骤数组（custom 才有；其他类型为空） */
    private List<String> requiredSteps;
    private Integer maxDurationSeconds;
    /** 该场景剩余训练次数（null=不限） */
    private Integer remainingSceneAttempts;
    /** 剩余考核次数（null=不限） */
    private Integer remainingExamAttempts;
    private List<Check> checks;

    @Data
    public static class Check {
        private String code;
        private boolean passed;
        private String message;

        public static Check pass(String code) {
            Check check = new Check();
            check.setCode(code);
            check.setPassed(true);
            return check;
        }

        public static Check fail(String code, String message) {
            Check check = new Check();
            check.setCode(code);
            check.setPassed(false);
            check.setMessage(message);
            return check;
        }
    }
}
