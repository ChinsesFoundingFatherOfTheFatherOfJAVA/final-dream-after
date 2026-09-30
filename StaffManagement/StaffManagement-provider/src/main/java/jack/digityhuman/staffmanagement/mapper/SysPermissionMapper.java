package jack.digityhuman.staffmanagement.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import jack.digityhuman.staffmanagement.entity.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * {@code sys_permission} 的数据访问（菜单级权限点，FP-14）。
 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    List<SysPermission> selectRowsByIds(@Param("ids") List<Long> ids);
}
