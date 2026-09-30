package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
    int insertIgnore(@Param("userId") Long userId, @Param("roleId") Long roleId,
                     @Param("addedBy") String addedBy, @Param("addedAt") LocalDateTime addedAt);

    List<SysUserRole> selectByRoleId(@Param("roleId") Long roleId);

    List<SysUserRole> selectByUserId(@Param("userId") Long userId);

    List<SysUserRole> selectByUserIds(@Param("userIds") List<Long> userIds);

    List<RoleCountRow> countByRoleIds(@Param("roleIds") List<Long> roleIds);

    class RoleCountRow {
        private Long roleId;
        private Integer userCount;

        public Long getRoleId() {
            return roleId;
        }

        public void setRoleId(Long roleId) {
            this.roleId = roleId;
        }

        public Integer getUserCount() {
            return userCount;
        }

        public void setUserCount(Integer userCount) {
            this.userCount = userCount;
        }
    }
}
