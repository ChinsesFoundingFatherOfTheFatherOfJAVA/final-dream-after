package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.TrainingRoundAcoustic;

/** 每轮语音声学特征 Mapper（v1.2，只写不读：评分链路本轮不消费） */
public interface TrainingRoundAcousticMapper extends BaseMapper<TrainingRoundAcoustic> {
}
