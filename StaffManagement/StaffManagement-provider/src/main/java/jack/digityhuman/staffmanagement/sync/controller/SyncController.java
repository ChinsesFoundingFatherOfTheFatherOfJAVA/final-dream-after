package jack.digityhuman.staffmanagement.sync.controller;

import jack.digityhuman.staffmanagement.common.ListVo;
import jack.digityhuman.staffmanagement.sync.service.OracleSyncService;
import jack.digityhuman.staffmanagement.sync.vo.SyncLogVo;
import jack.digityhuman.staffmanagement.user.service.UserAdminService;
import jack.digityhuman.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 同步任务的运维入口（FP-17，《03-接口文档》§2.7）。
 *
 * <p>两个接口都只给超管：手动全量会重写 3.4 万行基础字段，状态查询里带着 Oracle 侧的水位与人数，
 * 都不该是普通管理员能碰的东西。
 */
@RestController
@RequestMapping("/api/sm/admin/sync")
public class SyncController {

    /** 状态列表固定返回最近 10 次：再多也没人看，且这本就是排查接口。 */
    private static final int RECENT_LOG_LIMIT = 10;

    private final OracleSyncService syncService;
    private final UserAdminService userAdminService;

    public SyncController(OracleSyncService syncService, UserAdminService userAdminService) {
        this.syncService = syncService;
        this.userAdminService = userAdminService;
    }

    /**
     * 手动触发同步，后台执行、立即返回。
     *
     * @param full true=全量（忽略水位），false=增量
     */
    @PostMapping("/trigger")
    public ApiResponse<Map<String, Boolean>> trigger(@RequestParam(defaultValue = "false") boolean full) {
        userAdminService.requireSuperAdmin();
        syncService.trigger(full);
        return ApiResponse.ok(Map.of("triggered", true));
    }

    /** 最近 10 次同步记录。 */
    @GetMapping("/status")
    public ApiResponse<ListVo<SyncLogVo>> status() {
        userAdminService.requireSuperAdmin();
        return ApiResponse.ok(ListVo.of(syncService.recentLogs(RECENT_LOG_LIMIT)));
    }
}
