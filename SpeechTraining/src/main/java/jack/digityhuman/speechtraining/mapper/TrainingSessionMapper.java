package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import lombok.Data;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/** 练习会话 Mapper */
public interface TrainingSessionMapper extends BaseMapper<TrainingSession> {

    /**
     * 加行锁读取会话（排他读，直到事务结束）。
     * <p>学员"重复提交/双开页面"时两个请求会在锁上串行，后一个因此在锁内重读到
     * 已前进的 current_node_id，从而能判定自己的回答已过期。
     */
    TrainingSession selectForUpdate(@Param("sessionId") Long sessionId);

    /**
     * 平台总览的区间汇总：记录数（学习人次）、完成数、去重活跃人数、时长合计。
     * <p>为什么不把分桶结果相加：同一个人可能横跨多个桶，各桶 distinct 相加会重复计数，
     * 区间整体必须自己去重一次。单表统计，不 JOIN 他域表（后端架构总纲 第 5 节）。
     */
    SessionTotalsRow selectSessionTotals(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * 平台总览的趋势分桶：一条 GROUP BY 出全部有数据的桶，桶的空缺由 Service 补零。
     * <p>逐日循环查询会产生 N+1（02-技术实现方案.md 第 3 节明确禁止），粒度切换交给 SQL；
     * 归属时间字段为 start_time（03-接口文档.md 第 3.4 节）。
     */
    List<SessionBucketRow> selectSessionBuckets(@Param("from") LocalDateTime from,
                                                @Param("to") LocalDateTime to,
                                                @Param("bucketUnit") String bucketUnit);

    /** 区间汇总行；库里 duration_seconds 可为 NULL，SQL 侧已 coalesce 成 0。 */
    @Data
    class SessionTotalsRow {
        private long sessionCount;
        private long finishedCount;
        private long activeUsers;
        private long durationSeconds;
    }

    /** 分桶行；bucket 为 yyyy-MM-dd 形态的桶起点，与 Service 生成的桶键同一套规则。 */
    @Data
    class SessionBucketRow {
        private String bucket;
        private long activeUsers;
        private long durationSeconds;
    }
}
