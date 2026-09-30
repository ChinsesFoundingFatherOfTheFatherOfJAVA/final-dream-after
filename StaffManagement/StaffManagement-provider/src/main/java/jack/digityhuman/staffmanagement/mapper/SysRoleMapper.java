package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

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
    List<SysRole> selectRoles(@Param("keyword") String keyword);

    List<SysRole> selectRowsByIds(@Param("ids") List<Long> ids);
}
