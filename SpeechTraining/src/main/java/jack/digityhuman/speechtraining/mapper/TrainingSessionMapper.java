package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.TrainingSession;
import org.apache.ibatis.annotations.Param;

/** 练习会话 Mapper */
public interface TrainingSessionMapper extends BaseMapper<TrainingSession> {

    /**
     * 加行锁读取会话（排他读，直到事务结束）。
     * <p>学员"重复提交/双开页面"时两个请求会在锁上串行，后一个因此在锁内重读到
     * 已前进的 current_node_id，从而能判定自己的回答已过期。
     */
    TrainingSession selectForUpdate(@Param("sessionId") Long sessionId);
}
