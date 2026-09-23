package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysRole;
import jack.digityhuman.staffmanagement.mapper.row.RoleListRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code sys_role} 的数据访问。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 角色列表带授权人数（FP-12）。
     *
     * <p>必须 LEFT JOIN：INNER JOIN 会把 0 人角色（刚创建的"导师"）整行滤掉，
     * 创建完列表里就看不见了。{@code count(ur.id)} 而不是 {@code count(*)}，
     * 否则无成员角色会被算成 1 人。
     */
    @Select("""
            <script>
            select r.id, r.name, r.created_by, r.created_at, count(ur.id) user_count
            from sys_role r
            left join sys_user_role ur on ur.role_id = r.id
            <where>
              <if test="keyword != null and keyword != ''">
                and r.name like concat('%', #{keyword}, '%')
              </if>
            </where>
            group by r.id, r.name, r.created_by, r.created_at
            order by r.id
            </script>
            """)
    List<RoleListRow> selectRoleList(@Param("keyword") String keyword);
}
