package jack.digityhuman.examservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.examservice.entity.ExamFaceBaseline;

/**
 * 人脸基准照（07 附录 C4）。按 user_id 查基准照属单表条件查询，
 * 统一用 MP 的 {@code selectOne(new LambdaQueryWrapper<ExamFaceBaseline>().eq(ExamFaceBaseline::getUserId, id))}，
 * 不额外写 XML（AGENTS §3.2）。
 */
public interface ExamFaceBaselineMapper extends BaseMapper<ExamFaceBaseline> {
}
