package jack.digityhuman.atualcombattraining.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.atualcombattraining.entity.ActRandomScript;
import org.apache.ibatis.annotations.Mapper;

/**
 * 随机练习脚本 Mapper（发布期预生成分档，见 {@link ActRandomScript} 的类注释）
 */
@Mapper
public interface ActRandomScriptMapper extends BaseMapper<ActRandomScript> {
}
