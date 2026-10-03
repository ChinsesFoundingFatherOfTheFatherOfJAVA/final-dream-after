package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.entity.KngCourseTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程任务 Mapper：MP 单表 CRUD（任务聚合/统计走 Service 批量查 + Map 组装，不跨表 JOIN）。
 */
@Mapper
public interface KngCourseTaskMapper extends BaseMapper<KngCourseTask> {
}
