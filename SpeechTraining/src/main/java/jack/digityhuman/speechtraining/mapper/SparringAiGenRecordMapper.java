package jack.digityhuman.speechtraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.speechtraining.entity.SparringAiGenRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI生成话术三步会话留痕 Mapper（sparring_ai_gen_record，本应用自有表）。
 */
@Mapper
public interface SparringAiGenRecordMapper extends BaseMapper<SparringAiGenRecord> {
}
