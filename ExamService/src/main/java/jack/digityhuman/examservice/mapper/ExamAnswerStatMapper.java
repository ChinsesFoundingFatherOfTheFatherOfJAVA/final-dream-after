package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamAnswerStat;
import org.apache.ibatis.annotations.Mapper;

/** exam_answer_stat Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamAnswerStat.xml）。 */
@Mapper
public interface ExamAnswerStatMapper extends BaseMapper<ExamAnswerStat> {
}
