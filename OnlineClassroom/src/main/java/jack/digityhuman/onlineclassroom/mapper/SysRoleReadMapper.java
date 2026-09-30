package jack.digityhuman.onlineclassroom.mapper;

import jack.digityhuman.onlineclassroom.entity.SysRoleRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysRoleReadMapper {
    long countByKeyword(@Param("keyword") String keyword);

    List<SysRoleRead> selectPage(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit);
}
