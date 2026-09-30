package jack.digityhuman.onlineclassroom.mapper;

import jack.digityhuman.onlineclassroom.dto.KngReadDtos;
import jack.digityhuman.onlineclassroom.entity.SysUserRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysUserReadMapper {
    List<SysUserRead> selectByIds(@Param("ids") List<String> ids);

    List<String> selectIdsByKeyword(@Param("keyword") String keyword);

    List<KngReadDtos.DeptPathCountRow> countGroupByDeptPath();

    long countDistinctPositions(@Param("keyword") String keyword);

    List<KngReadDtos.PositionCountRow> selectPositionPage(
            @Param("keyword") String keyword,
            @Param("offset") int offset,
            @Param("limit") int limit);
}
