package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamQuestionPoint;
import org.apache.ibatis.annotations.Mapper;

/** exam_question_point Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamQuestionPoint.xml）。 */
@Mapper
public interface ExamQuestionPointMapper extends BaseMapper<ExamQuestionPoint> {
}
