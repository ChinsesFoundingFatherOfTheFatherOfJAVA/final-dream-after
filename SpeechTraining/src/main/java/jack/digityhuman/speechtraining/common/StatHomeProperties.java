package jack.digityhuman.speechtraining.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 平台总览（管理端首页）统计端点的边界配置。
 *
 * <p>为什么单独成配置对象而不是散落的 {@code @Value}：这两个阈值是契约（01-需求文档.md 第 5 节）
 * 里唯一允许各域自行取值的地方，单测需要把它们调小才能覆盖"区间截断"和"分桶超限"两个分支，
 * 集中成一个可构造的 POJO 比读环境变量更好断言。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stat.home")
public class StatHomeProperties {

    /** 允许查询的最大区间天数，默认 730；超出时保留 to、把 from 前移到 to-该天数，并置 range.truncated。 */
    private int maxRangeDays = 730;

    /** 单次返回的最大分桶数，默认 400；超出直接返回 40004，不静默合并桶（合并会让折线口径失真）。 */
    private int seriesMaxBuckets = 400;
}
