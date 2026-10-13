package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamPoint;
import org.apache.ibatis.annotations.Mapper;

/** exam_point Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamPoint.xml）。 */
@Mapper
public interface ExamPointMapper extends BaseMapper<ExamPoint> {
}
