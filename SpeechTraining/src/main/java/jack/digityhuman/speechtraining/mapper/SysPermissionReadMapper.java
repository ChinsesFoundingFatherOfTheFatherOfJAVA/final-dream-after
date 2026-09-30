package jack.digityhuman.speechtraining.mapper;

import jack.digityhuman.speechtraining.entity.SysPermissionRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysPermissionReadMapper {
    List<SysPermissionRead> selectByIds(@Param("ids") List<Long> ids);
}
