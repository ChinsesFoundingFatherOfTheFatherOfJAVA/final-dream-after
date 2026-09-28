package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysSyncLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code sys_sync_log} 的数据访问（同步水位，FP-17）。
 */
@Mapper
public interface SysSyncLogMapper extends BaseMapper<SysSyncLog> {

    /**
     * 当前增量水位：最近一条 <b>SUCCESS</b> 记录的 watermark。
     *
     * <p>状态过滤是这个查询存在的全部意义——上一轮 FAILED 时水位必须退回更早的成功值，
     * 否则失败那轮没同步到的员工永远没人再拉。
     *
     * @return 无成功记录（首次部署）时为 null，调用方按全量处理
     */
    @Select("select watermark from sys_sync_log where status = 'SUCCESS' order by id desc limit 1")
    LocalDateTime selectCurrentWatermark();

    /** 最近若干次同步记录（倒序），供 {@code GET /api/sm/admin/sync/status} 排查用。 */
    @Select("select id, sync_type, watermark, total, inserted, updated, deleted, skipped, status, error_msg,"
            + " started_at, finished_at from sys_sync_log order by id desc limit #{limit}")
    List<SysSyncLog> selectRecent(@Param("limit") int limit);
}
