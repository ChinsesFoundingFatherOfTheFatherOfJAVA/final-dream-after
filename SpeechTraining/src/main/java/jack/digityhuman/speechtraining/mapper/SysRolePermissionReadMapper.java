package jack.digityhuman.speechtraining.mapper;

import jack.digityhuman.speechtraining.entity.SysRolePermissionRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysRolePermissionReadMapper {
    List<SysRolePermissionRead> selectByRoleIds(@Param("roleIds") List<Long> roleIds);
}
