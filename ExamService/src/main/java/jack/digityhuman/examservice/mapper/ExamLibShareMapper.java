package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamLibShare;
import org.apache.ibatis.annotations.Mapper;

/** exam_lib_share Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamLibShare.xml）。 */
@Mapper
public interface ExamLibShareMapper extends BaseMapper<ExamLibShare> {
}
