package jack.digityhuman.speechtraining.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 每轮语音声学特征实体（v1.2）
 * <p>由豆包 ASR 的分句/逐字时间戳汇总而来，一条语音学员消息对应一行。
 * <b>本轮只留存不参与评分</b>：语气、流利度等语音维度此前无任何客观特征可用，
 * 先把数据攒下来，是否据此打分、怎么打分待产品确认后再改评分链路。
 */
@Data
@TableName("training_round_acoustic")
public class TrainingRoundAcoustic {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long sessionId;
    /** 对应的学员语音消息ID（撤回的回答同样留存，撤回不代表没说过话） */
    private Long messageId;
    private Integer roundNo;
    /** 录音时长（毫秒，本端 WAV 头，含静音） */
    private Integer audioMs;
    /** 净发声时长（毫秒，各分句首尾跨度之和） */
    private Integer speechMs;
    private Integer utteranceCount;
    /** 停顿次数（相邻分句间隔 ≥300ms 才计） */
    private Integer pauseCount;
    /** 停顿总时长（毫秒，与 pauseCount 同一口径） */
    private Integer pauseTotalMs;
    /** 最长一次分句间隔（毫秒，不设阈值） */
    private Integer maxPauseMs;
    /** 识别文本长度（含标点） */
    private Integer textLength;
    /** 豆包检出语种（粤语为 speech_dia_cant；未检出为空串） */
    private String lidLang;
    /** 末个分句是否终结：1=是, 0=否, null=无分句 */
    private Integer definite;
    /** 豆包逐字时间戳原文（JSON 数组字符串，字段名按服务端返回原样保留） */
    private String wordsJson;
    /** 豆包回传的音频时长（毫秒），与 audio_ms 不一致说明 WAV 封装头有问题 */
    private Integer serverAudioMs;
    private LocalDateTime createdAt;
}
