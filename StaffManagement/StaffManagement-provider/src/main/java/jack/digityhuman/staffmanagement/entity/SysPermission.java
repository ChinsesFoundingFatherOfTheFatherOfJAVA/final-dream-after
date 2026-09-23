package jack.digityhuman.staffmanagement.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 权限点（{@code sys_permission}，FP-14）。
 *
 * <p>菜单级粒度：一个权限点对应管理端一个页面，不含按钮级与数据范围。
 * 内容由启动 seeding 按《02》§4.5 的固定清单写入，之后不随代码热更新，
 * 需要增删菜单时改 {@code DataInitializer} 的清单并重启。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 权限码，如 {@code org:employee}；前端路由 meta.permission 用它比对。 */
    private String code;

    private String name;

    /** 父节点 ID，0 表示根节点（分组节点本身不带 route_path，不可勾选为权限）。 */
    private Long parentId;

    /** 前端路由，如 {@code /udp/orgoumgmt}；分组节点为 null。 */
    private String routePath;

    private Integer sort;
}
