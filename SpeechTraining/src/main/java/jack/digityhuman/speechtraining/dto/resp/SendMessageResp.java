package jack.digityhuman.speechtraining.dto.resp;

import lombok.Data;

import java.util.List;

/**
 * 发送消息响应（流转判定，接口文档 2.3）
 */
@Data
public class SendMessageResp {

    /** true 时前端撤回气泡并弹要点提示弹窗 */
    private Boolean withdrawn;
    /** 当前学员节点要点列表 */
    private List<String> keyPoints;
    /** 是否通过流转条件 */
    private Boolean passed;
    /** true 时前端跳转报告页 */
    private Boolean finished;
    private Integer finishedRounds;
    private Integer totalRounds;
    /** 通过且未结束时：下一个陪练者问题 */
    private MessageResp nextTrainerMessage;

    /** 未通过：撤回 + 要点提示 */
    public static SendMessageResp withdrawn(List<String> keyPoints, int finishedRounds, int totalRounds) {
        SendMessageResp resp = new SendMessageResp();
        resp.setWithdrawn(true);
        resp.setKeyPoints(keyPoints);
        resp.setPassed(false);
        resp.setFinished(false);
        resp.setFinishedRounds(finishedRounds);
        resp.setTotalRounds(totalRounds);
        return resp;
    }

    /** 通过且流转到下一个大节点：返回下一句陪练者问 */
    public static SendMessageResp passed(List<String> keyPoints, MessageResp nextTrainerMessage,
                                         int finishedRounds, int totalRounds) {
        SendMessageResp resp = new SendMessageResp();
        resp.setWithdrawn(false);
        resp.setKeyPoints(keyPoints);
        resp.setPassed(true);
        resp.setFinished(false);
        resp.setFinishedRounds(finishedRounds);
        resp.setTotalRounds(totalRounds);
        resp.setNextTrainerMessage(nextTrainerMessage);
        return resp;
    }

    /** 通过且流程结束 */
    public static SendMessageResp finished(List<String> keyPoints, int finishedRounds, int totalRounds) {
        SendMessageResp resp = new SendMessageResp();
        resp.setWithdrawn(false);
        resp.setKeyPoints(keyPoints);
        resp.setPassed(true);
        resp.setFinished(true);
        resp.setFinishedRounds(finishedRounds);
        resp.setTotalRounds(totalRounds);
        return resp;
    }
}
