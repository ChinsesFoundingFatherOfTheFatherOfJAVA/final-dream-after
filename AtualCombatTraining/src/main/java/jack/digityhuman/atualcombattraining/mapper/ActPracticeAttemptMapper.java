package jack.digityhuman.atualcombattraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.atualcombattraining.dto.AdminStatDtos;
import jack.digityhuman.atualcombattraining.entity.ActPracticeAttempt;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ActPracticeAttemptMapper extends BaseMapper<ActPracticeAttempt> {

    /**
     * 管理端首页统计：按 granularity 分桶的本域活跃趋势（SQL 见 mapper/ActPracticeAttemptMapper.xml）。
     * <p>
     * 只返回「有数据的桶」；区间内没有记录的桶由 Service 补零，
     * 因为「区间里有哪些桶」属于时间语义，不该再由 SQL 数一遍日期。
     *
     * @param from        开始时间 yyyy-MM-dd HH:mm:ss（含）
     * @param to          结束时间 yyyy-MM-dd HH:mm:ss（含）
     * @param readyStatus 需要排除的 status 值（常量 ready，用参数绑定而不是拼字符串）
     * @param granularity day / week / month，决定桶键的格式
     * @return 按桶键升序的分桶结果
     */
    List<AdminStatDtos.BucketRow> selectStatBuckets(@Param("from") String from,
                                                    @Param("to") String to,
                                                    @Param("readyStatus") String readyStatus,
                                                    @Param("granularity") String granularity);

    /**
     * 管理端首页统计：区间合计（去重人数 / 活跃秒数 / 练习人次 / 完成人次）。
     * <p>
     * 与 {@link #selectStatBuckets} 分开而不是复用分桶结果：去重人数不可跨桶相加，
     * 必须由区间聚合直接给出，否则趋势点数一多人数就会被重复累计。
     *
     * @param from            开始时间 yyyy-MM-dd HH:mm:ss（含）
     * @param to              结束时间 yyyy-MM-dd HH:mm:ss（含）
     * @param readyStatus     需要排除的 status 值
     * @param completedStatus 计入完成人次的 status 值（completed）
     * @return 区间合计；区间内无记录时人数/人次为 0、时长为 null
     */
    AdminStatDtos.TotalsRow selectStatTotals(@Param("from") String from,
                                            @Param("to") String to,
                                            @Param("readyStatus") String readyStatus,
                                            @Param("completedStatus") String completedStatus);
}
