package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamCatalogAdmin;
import org.apache.ibatis.annotations.Mapper;

/** exam_catalog_admin Mapper（单表 CRUD；自定义 SQL 进 resources/mapper/ExamCatalogAdmin.xml）。 */
@Mapper
public interface ExamCatalogAdminMapper extends BaseMapper<ExamCatalogAdmin> {
}
