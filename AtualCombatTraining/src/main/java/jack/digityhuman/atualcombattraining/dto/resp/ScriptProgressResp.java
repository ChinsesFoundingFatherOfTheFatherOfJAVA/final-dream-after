package jack.digityhuman.atualcombattraining.dto.resp;

import lombok.Data;

/**
 * 剧本初始化进度响应（03 文档 2.6）
 * <p>
 * progress = round(completed / total * 100)；无案例时按 100 返回。
 * status 为主题当前状态（1=发布中/3=已发布），前端 status=1 时 5s 轮询、变 3 停止。
 */
@Data
public class ScriptProgressResp {

    private Integer total;
    private Integer completed;
    private Integer failed;
    private Integer progress;
    private Integer status;
}