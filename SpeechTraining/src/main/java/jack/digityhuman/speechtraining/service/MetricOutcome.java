package jack.digityhuman.speechtraining.service;

import java.util.List;

/**
 * 后端量化指标的判定结果。
 *
 * @param score        维度得分
 * @param factText     追加进维度 rule 的事实文本；null 表示该事实已由专门入参交给 Dify
 * @param comment      学员端维度总评
 * @param goodPoints   做得好的点
 * @param improvements 待改进点与建议
 */
public record MetricOutcome(int score, String factText, String comment,
                            List<String> goodPoints, List<String> improvements) {

    /** 指标要求的数据一次都没有：记 0 分并占权重，而不是把这一维从总分里拿掉。 */
    public static MetricOutcome zero(String comment) {
        return new MetricOutcome(0, null, comment, List.of(), List.of(comment));
    }
}
