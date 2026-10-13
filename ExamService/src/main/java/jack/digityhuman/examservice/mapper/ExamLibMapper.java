package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamLib;
import org.apache.ibatis.annotations.Mapper;

/** exam_lib Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamLib.xml）。 */
@Mapper
public interface ExamLibMapper extends BaseMapper<ExamLib> {
}
