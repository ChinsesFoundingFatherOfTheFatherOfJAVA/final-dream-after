package jack.digityhuman.speechtraining.service;

import jack.digityhuman.speechtraining.common.DimensionMetricConfig;

import java.nio.file.Path;
import java.util.List;

/**
 * 语气维度评分（FP-30 Phase 2，PoC 已验证）。
 * 对每条进入评分的语音轮调用千问AI平台音频模型 3 次取中，再按各轮识别字数加权。
 */
public interface ToneScoringService {

    /** 把 /audio/** 形式的访问 URL 解析为本地音频文件路径。 */
    Path resolveAudioFile(String audioUrl);

    /**
     * 语气维度评分。任何一轮没有有效结果 → 整维度返回 null，绝不编造分数。
     */
    MetricOutcome measureTone(DimensionMetricConfig metric, List<ToneRoundInput> rounds);

    /** 一条进入评分的语音轮输入。 */
    record ToneRoundInput(Long messageId, Path audioFile, String text, int charCount) {
    }
}
