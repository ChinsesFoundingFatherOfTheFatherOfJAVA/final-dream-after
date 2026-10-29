package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.dto.KngAiSourceDtos;
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

    /**
     * AI 素材源数量（AI 设计助手 / AI 出题接入在线课堂）。
     * <p>
     * 固定条件 status=2 AND on_sale=1 AND type=1，外加调用方传入的候选 id 集合
     * （拥有文档源文件的课件）。COUNT 与列表共用同一套筛选条件，保证 total 是真实总数。
     */
    long countAiSources(
            @Param("query") KngAiSourceDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds,
            @Param("candidateIds") List<String> candidateIds);

    /** AI 素材源分页列表（排序白名单见 XML，不拼接任何外部输入到 SQL 片段） */
    List<KngCourseware> selectPageAiSources(
            @Param("query") KngAiSourceDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds,
            @Param("candidateIds") List<String> candidateIds,
            @Param("orderBy") String orderBy,
            @Param("orderType") String orderType,
            @Param("offset") int offset,
            @Param("limit") int limit);
}
