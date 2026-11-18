package jack.digityhuman.staffmanagement.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.user.service.UserStatService;
import jack.digityhuman.staffmanagement.user.vo.UserStatVo;
import org.springframework.stereotype.Service;

/**
 * 账号口径统计实现。
 *
 * <p>为什么用 MyBatis-Plus 的 selectCount 而不是写 XML：这是典型的单表条件计数（总纲第 5 节第 1 条
 * 「单表简单 CRUD 用 MyBatis-Plus」），不需要自定义 SQL；两条 count 都在 {@code sys_user.status} 的
 * 索引与主键上，8648 行量级下开销可忽略。
 */
@Service
public class UserStatServiceImpl implements UserStatService {

    private final SysUserMapper sysUserMapper;

    public UserStatServiceImpl(SysUserMapper sysUserMapper) {
        this.sysUserMapper = sysUserMapper;
    }

    /**
     * 启用口径固定为 {@code status = 1}（禁用账号登录返回 40103，见 SysUser 注释），
     * 未引入「逻辑删除」过滤是因为 {@code sys_user} 表没有 deleted 列——这一点必须在实现里写死，
     * 否则后来者会照着其它模块的习惯凭空加一个不存在的条件。
     */
    @Override
    public UserStatVo countUsers() {
        Long total = sysUserMapper.selectCount(null);
        Long enabled = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>().eq(SysUser::getStatus, 1));
        return new UserStatVo(
                enabled == null ? 0L : enabled,
                total == null ? 0L : total);
    }
}
