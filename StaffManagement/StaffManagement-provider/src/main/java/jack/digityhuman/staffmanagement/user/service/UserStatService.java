package jack.digityhuman.staffmanagement.user.service;

import jack.digityhuman.staffmanagement.user.vo.UserStatVo;

/**
 * 账号口径统计服务（平台预览看板 03-接口文档 第 2.1 节）。
 *
 * <p>放在 user 领域包内，遵守 StaffManagement AGENTS 第 2 节「新代码一律进领域包」。
 */
public interface UserStatService {

    /**
     * 统计启用人数与账号总数。
     *
     * <p>不走分页查询后再数总数：名册分页要带部门、关键字等条件且会回填大量字段，
     * 看板只要两个计数，直接用两条 count 走索引最省。
     */
    UserStatVo countUsers();
}
