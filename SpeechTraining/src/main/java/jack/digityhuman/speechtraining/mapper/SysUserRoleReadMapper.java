package jack.digityhuman.speechtraining.mapper;

import jack.digityhuman.speechtraining.entity.SysUserRoleRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysUserRoleReadMapper {
    List<SysUserRoleRead> selectByUserId(@Param("userId") Long userId);
}
