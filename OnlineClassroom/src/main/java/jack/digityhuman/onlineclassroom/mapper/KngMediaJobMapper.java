package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.entity.KngMediaJob;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 媒体处理任务 Mapper：MP 单表 CRUD 之外的分页/计数走本 XML（铁律：自定义 SQL 进 mapper/*.xml、外部输入 #{}）。
 *
 * <p>keyword 检索命中在课件表（title/catalog_name/created_by_name），禁跨表 JOIN——
 * Service 先单表查课件 id 集合，再以 {@code coursewareIds} 条件过滤本表，两步查询内存组装。
 */
@Mapper
public interface KngMediaJobMapper extends BaseMapper<KngMediaJob> {

    /** 按 job_type + 状态集合 + 可选课件 id 集合分页取任务（created_at 倒序，最新投递在前）。 */
    List<KngMediaJob> selectJobPage(
            @Param("jobType") Integer jobType,
            @Param("statuses") List<Integer> statuses,
            @Param("coursewareIds") List<String> coursewareIds,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 同 {@link #selectJobPage} 口径的总数（分页 total）。 */
    long countJobs(
            @Param("jobType") Integer jobType,
            @Param("statuses") List<Integer> statuses,
            @Param("coursewareIds") List<String> coursewareIds);
}
