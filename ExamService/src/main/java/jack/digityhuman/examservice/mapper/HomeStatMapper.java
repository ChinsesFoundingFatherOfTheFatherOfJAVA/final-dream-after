package jack.digityhuman.examservice.mapper;

import jack.digityhuman.examservice.dto.resp.HomeStatRespDtos;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端首页「平台总览」考试域统计查询（平台预览看板 接口文档 §2.5）。
 *
 * <p>为什么单独建一个 Mapper 而不是拆进 {@code ExamArrangeMapper}/{@code ExamAttemptMapper}：
 * 这些查询是「跨场次汇总」的只读统计，和单表 CRUD 的写入语义无关；集中在一处既方便按
 * 04-索引增补.sql 逐条核对走哪个索引，也避免给单表 CRUD Mapper 挂上聚合方法后被误用。
 *
 * <p>四条 SQL 全是单表查询、外部输入一律 {@code #{}} 绑定（架构总纲 §5）：
 * 考试总量/新增走 exam_arrange、覆盖人次走 exam_arrange_student、参考与活跃走 exam_attempt，
 * 不在 SQL 里 JOIN 三张表，卡片组装在 Service。
 */
public interface HomeStatMapper {

    /**
     * 按 granularity 分桶的参考记录聚合：桶内去重人数 + 时长合计（秒）。
     *
     * <p>过滤条件与 {@link #selectAttemptTotals} 完全一致，保证「趋势点相加」与卡片合计不打架。
     *
     * @param from        区间起（含）
     * @param to          区间止（含）
     * @param granularity day / week / month，决定桶表达式
     */
    List<HomeStatRespDtos.BucketRow> selectAttemptBuckets(@Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to,
                                                          @Param("granularity") String granularity);

    /**
     * 区间内的参考人次/通过人次/去重人数/时长合计。
     *
     * <p>为什么去重人数不按桶累加：同一人跨多个桶只算一个活跃用户，
     * 累加分桶的 distinct 会把一个人数成多次，必须单独一条 distinct 统计。
     */
    HomeStatRespDtos.AttemptTotalsRow selectAttemptTotals(@Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to);

    /** exam_arrange 的考试总量（全量）与本期新增（created_at 落区间），均为 deleted=0。 */
    HomeStatRespDtos.ArrangeTotalsRow selectArrangeTotals(@Param("from") LocalDateTime from,
                                                          @Param("to") LocalDateTime to);

    /** 覆盖人次：exam_arrange_student 的 deleted=0 记录数（不限区间，反映考试覆盖规模）。 */
    Long countCoveredUsers();
}
