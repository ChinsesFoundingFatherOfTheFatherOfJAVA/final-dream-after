package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.dto.resp.SendMessageResp;
import jack.digityhuman.speechtraining.dto.resp.SessionDetailResp;
import jack.digityhuman.speechtraining.dto.resp.StartSessionResp;
import jack.digityhuman.speechtraining.dto.resp.VoiceMessageResp;

/**
 * 流程引擎服务（学员端话术训练核心，FP-12/19/20/21）。
 * <p>
 * 适配实际存储结构：一个大节点（type 2=陪练者问学员 / 3=学员问陪练者）为【一行】training_node 记录，
 * 同时含 trainerQuestion（陪练者问）与 standardScript/keyPoints/流转条件（学员答侧），
 * 连线直接连接大节点。流转：开始节点 → 大节点 → … → 结束节点，按出边权重随机路由。
 */
public interface FlowEngineService {

    /**
     * 开始练习：创建会话，定位首个大节点，落库首个陪练者问（FP-19）
     */
    StartSessionResp start(Long projectId);

    /**
     * 发送消息（文字模式）：读上下文 → Dify 判定（事务外）→ 行锁内落库流转（FP-12/20）。
     * 判定与写阶段分离，避免数秒的 Dify 调用长时间持有会话行锁。
     */
    SendMessageResp sendMessage(Long sessionId, String content);

    /**
     * 发送语音消息（v1.1，FP-24/28；v1.2 双输入改造）：ASR 识别文本 → 与文字模式完全相同的流转判定。
     * 识别文本为空才短路（"没听到"），单字照常送 Dify 判定；声学特征随消息落 training_round_acoustic。
     *
     * @param asr ASR 识别结果（文本 + 语音时长 + 声学特征）
     * @param wav 学员录音原始字节（WAV），落盘为 /audio/asr/{messageId}.wav
     * @return 语音消息响应（识别文本 + 音频信息 + 判定结果）
     */
    VoiceMessageResp sendVoiceMessage(Long sessionId, SpeechService.AsrResult asr, byte[] wav);

    /**
     * 获取会话详情（训练页刷新恢复，FP-19；v1.1 增加 dialogueMode/trainerAvatar）
     */
    SessionDetailResp getSessionDetail(Long sessionId);

    /**
     * 退出训练：会话置"未完成"，保留消息，不生成报告（FP-13/19）
     */
    void exit(Long sessionId);
}
