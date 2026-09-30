package jack.digityhuman.onlineclassroom.mapper;

import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysUserRoleReadMapper {
    List<KngReadDtos.RoleCountRow> countByRoleIds(@Param("roleIds") List<String> roleIds);
}
