package jack.digityhuman.staffmanagement.user.vo;

/**
 * 管理端首页看板的账号计数（平台预览看板 03-接口文档 第 2.1 节）。
 *
 * <p>字段名与前端 {@code src/types/platformStat.ts} 一一对应，改名要同步接口文档与前端类型。
 *
 * @param enabledUsers 状态为启用的账号数（sys_user.status = 1）
 * @param totalUsers   账号总数
 */
public record UserStatVo(long enabledUsers, long totalUsers) {
}
