package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.dto.KngCourseDtos;
import jack.digityhuman.onlineclassroom.entity.KngCourse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 课程主表 Mapper：MP 单表 CRUD + 课程列表自定义筛选（SQL 见 KngCourseMapper.xml）。
 *
 * <p>外部输入一律 #{}，单表查询不跨表 JOIN；目录子树展开由 Service 先算好传入 catalogIds。
 */
@Mapper
public interface KngCourseMapper extends BaseMapper<KngCourse> {

    /** 课程列表总数（与 selectPageByQuery 同条件）。 */
    long countByQuery(
            @Param("query") KngCourseDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds);

    /** 课程列表分页（orderBy 已由 Service 白名单收敛为 updateTime/createdAt/platformNo）。 */
    List<KngCourse> selectPageByQuery(
            @Param("query") KngCourseDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds,
            @Param("orderBy") String orderBy,
            @Param("orderType") String orderType,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /** 课程列表全量（条件同上，无分页；预留给需要内存排序/全量处理的场景）。 */
    List<KngCourse> selectAllByQuery(
            @Param("query") KngCourseDtos.ListQuery query,
            @Param("catalogIds") List<String> catalogIds);

    /**
     * 统计 kng_course 内指定前缀的平台课程编号数量（供 ZXKT+yyyyMMdd+4位序号 递增生成的计数基础）。
     * 注意：与课件平台编号（KngCoursewareMapper.countPlatformNoByPrefix，查 kng_courseware）相互独立，
     * 两表各自计数，编号序列不交叉。
     */
    int countPlatformNoByPrefix(@Param("prefix") String prefix);
}
