package jack.digityhuman.staffmanagement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface TrainingProjectCascadeMapper {
    int countProtectedProjects(@Param("account") String account);
}
