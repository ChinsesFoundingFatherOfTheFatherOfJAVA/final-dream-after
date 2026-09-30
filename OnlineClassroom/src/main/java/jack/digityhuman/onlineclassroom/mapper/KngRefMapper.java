package jack.digityhuman.onlineclassroom.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.onlineclassroom.entity.KngRef;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface KngRefMapper extends BaseMapper<KngRef> {

    List<String> selectDistinctCoursewareIds();
}
