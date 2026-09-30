package jack.digityhuman.speechtraining.mapper;

import jack.digityhuman.speechtraining.entity.SysDepartmentRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SysDepartmentReadMapper {
    SysDepartmentRead selectById(@Param("id") Long id);
}
