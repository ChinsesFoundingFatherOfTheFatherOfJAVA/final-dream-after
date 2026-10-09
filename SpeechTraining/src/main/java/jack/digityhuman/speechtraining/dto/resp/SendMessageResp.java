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
    /**
     * true 时语音输入未被识别出任何内容（"没听到"而非"没答到点上"）。
     * 前端据此提示重说，而不是弹出要点弹窗——两种失败原因不同，混在一起会让学员以为自己答偏了。
     */
    private Boolean noSpeech;
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
    /** 流转途中经过的旁白消息（v1.2 role=3，微信撤回式灰条；可能连续多个旁白节点） */
    private List<MessageResp> narrationMessages;

    /** 未通过：撤回 + 要点提示 */
    public static SendMessageResp withdrawn(List<String> keyPoints, int finishedRounds, int totalRounds) {
        SendMessageResp resp = new SendMessageResp();
        resp.setWithdrawn(true);
        resp.setNoSpeech(false);
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
        resp.setNoSpeech(false);
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
        resp.setNoSpeech(false);
        resp.setKeyPoints(keyPoints);
        resp.setPassed(true);
        resp.setFinished(true);
        resp.setFinishedRounds(finishedRounds);
        resp.setTotalRounds(totalRounds);
        return resp;
    }

    /**
     * 语音未识别到内容：同样撤回气泡，但不带要点（此时学员还没有"作答"，要点提示没有意义）
     */
    public static SendMessageResp noSpeech(int finishedRounds, int totalRounds) {
        SendMessageResp resp = new SendMessageResp();
        resp.setWithdrawn(true);
        resp.setNoSpeech(true);
        resp.setKeyPoints(List.of());
        resp.setPassed(false);
        resp.setFinished(false);
        resp.setFinishedRounds(finishedRounds);
        resp.setTotalRounds(totalRounds);
        return resp;
    }
}
