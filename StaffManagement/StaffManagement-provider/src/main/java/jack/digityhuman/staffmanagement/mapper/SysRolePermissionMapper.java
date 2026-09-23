package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysRolePermission;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * {@code sys_role_permission} 的数据访问（FP-14 权限即时保存）。
 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {

    /**
     * 幂等勾选：重复点同一个复选框不会报错，也不会产生第二条关联（{@code uk_role_perm}）。
     *
     * @return 1=新增成功，0=已存在
     */
    @Insert("insert ignore into sys_role_permission (role_id, permission_id) values (#{roleId}, #{permissionId})")
    int insertIgnore(@Param("roleId") Long roleId, @Param("permissionId") Long permissionId);
}
