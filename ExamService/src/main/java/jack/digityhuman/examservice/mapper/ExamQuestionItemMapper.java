package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamQuestionItem;
import org.apache.ibatis.annotations.Mapper;

/** exam_question_item Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamQuestionItem.xml）。 */
@Mapper
public interface ExamQuestionItemMapper extends BaseMapper<ExamQuestionItem> {
}
