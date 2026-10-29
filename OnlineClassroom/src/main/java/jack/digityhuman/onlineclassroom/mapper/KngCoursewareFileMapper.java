package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.entity.KngCoursewareFile;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface KngCoursewareFileMapper extends BaseMapper<KngCoursewareFile> {

    /**
     * 拥有文档类源文件的课件 id（AI 素材源接入用）。
     * <p>
     * 只读本表：跨表条件由 Service 把结果当作"候选课件 id 集合"传给课件查询，
     * 符合持久层规范（XML 不跨表 JOIN，跨表数据 Service 组装）。
     *
     * @param extensions 允许的扩展名（小写，不含点）
     * @return 去重后的课件 id
     */
    List<String> selectCoursewareIdsWithExtensions(List<String> extensions);

    /**
     * 一批课件的源文件，按 sort_order, id 升序。
     * <p>
     * 用显式 XML 而不是 LambdaQueryWrapper：列表页要对一批课件一次性对齐"第一个文档源文件"，
     * 排序规则写在 SQL 里比在 Service 拼条件更稳（也避免实体列缓存未初始化时的 lambda 解析问题）。
     *
     * @param coursewareIds 课件 id 集合（调用方保证非空）
     * @return 源文件行（可能含非文档文件，过滤在 Service 做）
     */
    List<KngCoursewareFile> selectByCoursewareIdsOrdered(@Param("coursewareIds") List<String> coursewareIds);
}
