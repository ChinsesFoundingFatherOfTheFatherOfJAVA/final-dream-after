package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.ProjectStudent;

public interface ProjectStudentMapper extends BaseMapper<ProjectStudent> {

    /**
     * 覆盖人次：project_student 全量 distinct user_id，不按统计区间过滤。
     *
     * <p>它是学习率/完成率的分母，反映"项目覆盖规模"而不是"本期参与规模"；
     * 若按区间过滤，分母会随筛选变化导致比率忽高忽低，与契约口径不符（01-需求文档.md 第 2.3 节）。
     */
    long countDistinctUsers();
}
