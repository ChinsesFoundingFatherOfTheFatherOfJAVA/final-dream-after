package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jack.digityhuman.staffmanagement.entity.SysUserRole;
import jack.digityhuman.staffmanagement.mapper.row.RoleMemberRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@code sys_user_role} 的数据访问（角色成员，FP-15/16）。
 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 幂等添加成员：靠 {@code uk_user_role(user_id, role_id)} + INSERT IGNORE。
     *
     * @return 1=真实新增，0=已在角色中被忽略；服务层据此汇总 {@code added}/{@code skipped}
     */
    @Insert("insert ignore into sys_user_role (user_id, role_id, added_by, added_at) "
            + "values (#{userId}, #{roleId}, #{addedBy}, #{addedAt})")
    int insertIgnore(@Param("userId") Long userId, @Param("roleId") Long roleId,
                     @Param("addedBy") String addedBy, @Param("addedAt") LocalDateTime addedAt);

    /**
     * 角色成员分页（FP-15）：一条 JOIN 把关联和用户信息一起取回，避免"先查 ID 再批量查人"两次往返。
     *
     * <p>分页由 MyBatis-Plus 的 {@code PaginationInnerInterceptor} 接管——它把 {@link Page}
     * 作为第一个参数时自动改写成 limit + count，所以这里手写 SQL 也不自己拼 limit。
     *
     * <p>部门条件与员工名册同一套：等值命中"就挂在该部门"，LIKE 前缀命中子部门，
     * 模式里的 {@code \_} 已在服务层转义过，防止下划线被当成单字符通配符。
     *
     * <p>{@code byAccount} 用布尔开关而不是把列名传进来：搜索字段来自用户输入，
     * 拼进 SQL 就是注入口，这里只在两条写死的语句之间二选一。
     */
    @Select("""
            <script>
            select ur.id, ur.user_id, ur.added_by, ur.added_at,
                   u.name, u.account, u.status, u.dept_name, u.position_name
            from sys_user_role ur
            join sys_user u on u.id = ur.user_id
            where ur.role_id = #{roleId}
            <if test="deptPath != null">
              and (u.dept_path = #{deptPath} or u.dept_path like #{childPathPattern})
            </if>
            <if test="status != null">
              and u.status = #{status}
            </if>
            <if test="keyword != null">
              <choose>
                <when test="byAccount">and u.account like concat('%', #{keyword}, '%')</when>
                <otherwise>and u.name like concat('%', #{keyword}, '%')</otherwise>
              </choose>
            </if>
            order by ur.added_at desc, ur.id desc
            </script>
            """)
    List<RoleMemberRow> selectMembers(Page<RoleMemberRow> page,
                                      @Param("roleId") Long roleId,
                                      @Param("deptPath") String deptPath,
                                      @Param("childPathPattern") String childPathPattern,
                                      @Param("status") Integer status,
                                      @Param("keyword") String keyword,
                                      @Param("byAccount") boolean byAccount);
}
