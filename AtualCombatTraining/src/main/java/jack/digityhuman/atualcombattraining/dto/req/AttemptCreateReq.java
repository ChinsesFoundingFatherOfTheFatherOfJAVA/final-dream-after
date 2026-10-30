package jack.digityhuman.atualcombattraining.dto.req;

import lombok.Data;

/**
 * 创建练习 attempt 请求（学员端，契约 04 §学生端）
 * <p>
 * studyMode：1 训练 2 考核；practiceType：case/random/custom（与 studyMode 正交）。
 * customConfigJson 仅 custom 练习需要：{sceneBackground, actor:{...}, productProfileKeys[]}.
 */
@Data
public class AttemptCreateReq {

    private String themeId;
    private String scenarioId;
    private Integer studyMode;
    private String practiceType;
    /** 自定义练习配置（JSON 字符串；服务端会按 drill-script 请求格式透传） */
    private String customConfigJson;
}
