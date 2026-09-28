package jack.digityhuman.staffmanagement.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 用户删除时需要访问的话术训练关联表。
 *
 * <p>这些表与 {@code sys_user} 同在 final_dream 库；本模块只做级联清理与负责人保护，
 * 不把 SpeechTraining 的实体和业务代码复制过来。
 */
@Mapper
public interface UserCascadeMapper {

    @Select("select count(*) from training_project"
            + " where responsible_person = #{account} or created_by = #{account}")
    int countProtectedProjects(@Param("account") String account);

    @Delete("delete from project_student where user_id = #{account}")
    int deleteProjectStudents(@Param("account") String account);
}
