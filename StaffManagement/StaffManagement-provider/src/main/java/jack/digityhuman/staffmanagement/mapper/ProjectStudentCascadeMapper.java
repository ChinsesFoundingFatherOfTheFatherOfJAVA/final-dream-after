package jack.digityhuman.staffmanagement.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectStudentCascadeMapper {
    int deleteByUserAccount(@Param("account") String account);
}
