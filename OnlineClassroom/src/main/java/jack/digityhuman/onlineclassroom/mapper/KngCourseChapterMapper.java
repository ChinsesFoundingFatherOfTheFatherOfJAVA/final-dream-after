package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.entity.KngCourseChapter;
import org.apache.ibatis.annotations.Mapper;

/**
 * 课程章节 Mapper：MP 单表 CRUD（章节列表/整体保存由 Service 用 MP 条件构造完成，无自定义 SQL）。
 */
@Mapper
public interface KngCourseChapterMapper extends BaseMapper<KngCourseChapter> {
}
