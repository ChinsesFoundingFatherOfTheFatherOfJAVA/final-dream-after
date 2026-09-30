package jack.digityhuman.onlineclassroom.mapper;

import jack.digityhuman.onlineclassroom.entity.SysDepartmentRead;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface SysDepartmentReadMapper {
    List<SysDepartmentRead> selectAllOrdered();

    List<SysDepartmentRead> selectByIds(@Param("ids") List<String> ids);

    List<SysDepartmentRead> selectFirstLevel();

    SysDepartmentRead selectById(@Param("id") String id);
}
