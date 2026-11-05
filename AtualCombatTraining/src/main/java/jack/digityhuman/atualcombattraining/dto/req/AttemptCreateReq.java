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

    /**
     * 「再练一次」：沿用该 attempt 的脚本快照，不重新取档（用户拍板：同档取档不可复现，
     * 重取必然换人换脸）。为空 = 新建练习。
     * <p>
     * 只能传自己的 attempt（服务端校验归属）；仅对 case / random 生效，
     * custom 练习的脚本本来就是每次现场生成，没有"沿用"的语义。
     */
    private String reuseFromAttemptId;

    /** 随机练习的难度档（1/2/3）；为空时用主题设置里的难度 */
    private Integer difficulty;
}
