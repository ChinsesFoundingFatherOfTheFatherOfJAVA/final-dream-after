package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.TrainingProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

@Mapper
public interface TrainingProjectMapper extends BaseMapper<TrainingProject> {

    /**
     * 项目总量：契约要求全量、不限统计区间（01-需求文档.md 第 2.3 节），
     * 因此不能复用带时间条件的 count，单独一条最省事也最不易被后续改动带偏。
     */
    long countProjects();

    /**
     * 本期新增项目数：created_at 落区间（含两端），与"项目总量"分开的原因同 03-接口文档.md 第 3.4 节。
     */
    long countProjectsCreatedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);
}
