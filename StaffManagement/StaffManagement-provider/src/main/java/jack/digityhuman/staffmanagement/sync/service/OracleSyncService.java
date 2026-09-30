package jack.digityhuman.staffmanagement.sync.service;

import jack.digityhuman.staffmanagement.entity.SysSyncLog;
import jack.digityhuman.staffmanagement.sync.vo.SyncLogVo;

import java.util.List;

public interface OracleSyncService {
    void trigger(boolean full);

    SysSyncLog syncNow(boolean full);

    List<SyncLogVo> recentLogs(int limit);

    static int countSegments(String path) {
        int segments = 1;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '_') {
                segments++;
            }
        }
        return segments;
    }
}
