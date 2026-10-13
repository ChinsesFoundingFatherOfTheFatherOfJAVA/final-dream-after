package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamCatalog;
import org.apache.ibatis.annotations.Mapper;

/** exam_catalog Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamCatalog.xml）。 */
@Mapper
public interface ExamCatalogMapper extends BaseMapper<ExamCatalog> {
}
