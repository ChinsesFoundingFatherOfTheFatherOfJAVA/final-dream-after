package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.dto.KngCoursewareDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourseware;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KngCoursewareMapper extends BaseMapper<KngCourseware> {

    long countByQuery(
            @Param("query") KngCoursewareDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds,
            @Param("quotedIds") List<String> quotedIds);

    List<KngCourseware> selectPageByQuery(
            @Param("query") KngCoursewareDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds,
            @Param("quotedIds") List<String> quotedIds,
            @Param("orderBy") String orderBy,
            @Param("orderType") String orderType,
            @Param("offset") int offset,
            @Param("limit") int limit);

    List<KngCourseware> selectAllByQuery(
            @Param("query") KngCoursewareDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds,
            @Param("quotedIds") List<String> quotedIds);

    int countPlatformNoByPrefix(@Param("prefix") String prefix);
}
