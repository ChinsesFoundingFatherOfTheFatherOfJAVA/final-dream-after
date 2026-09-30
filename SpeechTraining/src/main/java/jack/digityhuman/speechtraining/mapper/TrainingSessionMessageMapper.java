package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.TrainingSessionMessage;
import org.apache.ibatis.annotations.Param;

/** 会话消息 Mapper */
public interface TrainingSessionMessageMapper extends BaseMapper<TrainingSessionMessage> {

    /**
     * 会话内当前最大消息序号，无消息时返回 -1。
     * <p>只取 MAX 而非把整会话消息拉进内存求最大值：一条消息两个气泡，每轮全量拉取是 O(n²)。
     */
    int selectMaxSeq(@Param("sessionId") Long sessionId);
}
