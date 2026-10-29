package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 剧本初始化进度响应（03 文档 2.6）
 * <p>
 * progress = round(completed / total * 100)；total 为案例数，没有案例时为 0。
 * sceneTotal / sceneCompleted 给详情横幅用：一个场景的案例全部 compiled 才算该场景完成。
 * status 为主题当前状态（1=发布中/3=已发布），前端 status=1 时 5s 轮询、变 3 停止。
 */
@Data
public class ScriptProgressResp {

    private Integer total;
    private Integer completed;
    private Integer failed;
    private Integer progress;
    private Integer status;
    /** 当前版本场景数，横幅分母 */
    private Integer sceneTotal;
    /** 案例已全部 compiled 的场景数，横幅分子 */
    private Integer sceneCompleted;
}