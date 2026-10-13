package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamQuestion;
import org.apache.ibatis.annotations.Mapper;

/** exam_question Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamQuestion.xml）。 */
@Mapper
public interface ExamQuestionMapper extends BaseMapper<ExamQuestion> {
}
