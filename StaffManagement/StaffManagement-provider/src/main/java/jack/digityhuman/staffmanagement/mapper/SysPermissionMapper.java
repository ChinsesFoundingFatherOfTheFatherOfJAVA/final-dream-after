package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@code sys_permission} 的数据访问（菜单级权限点，FP-14）。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 某角色已勾选的权限码。
     *
     * <p>只回 code：前端权限树用 code 判定勾选状态，ID 变化（重新 seeding）不影响已存关联的语义。
     */
    @Select("select p.code from sys_role_permission rp "
            + "join sys_permission p on p.id = rp.permission_id "
            + "where rp.role_id = #{roleId} order by p.sort, p.id")
    List<String> selectCodesByRoleId(@Param("roleId") Long roleId);

    /**
     * 某用户的全部权限码 = 其所有角色权限码的并集（去重）。
     *
     * <p>登录时一次性算出来下发给前端；超管不走这里，直接返回 {@code ["*"]}。
     */
    @Select("select distinct p.code from sys_user_role ur "
            + "join sys_role_permission rp on rp.role_id = ur.role_id "
            + "join sys_permission p on p.id = rp.permission_id "
            + "where ur.user_id = #{userId}")
    List<String> selectCodesByUserId(@Param("userId") Long userId);
}
