package jack.digityhuman.staffmanagement.sync;

import jack.digityhuman.staffmanagement.auth.AuthService;
import jack.digityhuman.staffmanagement.config.SyncProperties;
import jack.digityhuman.staffmanagement.entity.SysSyncLog;
import jack.digityhuman.staffmanagement.mapper.SysDepartmentMapper;
import jack.digityhuman.staffmanagement.mapper.SysSyncLogMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.user.UserAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OracleSyncServiceTest {

    @Test
    void 全量同步_补读LASTCHANGE为空的员工() {
        OracleReader reader = mock(OracleReader.class);
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysDepartmentMapper departmentMapper = mock(SysDepartmentMapper.class);
        SysSyncLogMapper syncLogMapper = mock(SysSyncLogMapper.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        SyncProperties properties = new SyncProperties();
        AuthService authService = mock(AuthService.class);
        UserAdminService userAdminService = mock(UserAdminService.class);

        OracleEmployee regular = employee("1001", "张三");
        OracleEmployee noLastChange = employee("1002", "李四");
        when(reader.readEmployeesAfter(null, null, 500)).thenReturn(List.of(regular));
        when(reader.readEmployeesWithNullLastChange()).thenReturn(List.of(noLastChange));
        when(reader.readDepartments()).thenReturn(List.of());
        when(userMapper.selectAllAccounts()).thenReturn(List.of());
        when(userMapper.selectOracleAccounts()).thenReturn(List.of());
        when(userMapper.selectDeptPathInfo()).thenReturn(List.of());
        when(userMapper.sumEnabledByDeptPath()).thenReturn(List.of());
        when(authService.encodeDefaultPassword()).thenReturn("hash");
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });

        OracleSyncService service = new OracleSyncService(reader, userMapper, departmentMapper,
                syncLogMapper, transactionTemplate, properties, authService, userAdminService);
        SysSyncLog log = service.syncNow(true);

        assertEquals(SysSyncLog.TYPE_FULL, log.getSyncType());
        assertEquals(2, log.getTotal());
        assertEquals(2, log.getInserted());
        verify(reader).readEmployeesWithNullLastChange();
        verify(syncLogMapper).insert(log);
    }

    private static OracleEmployee employee(String account, String name) {
        return new OracleEmployee(account, account, name, null, "D1", "部门",
                "杰克集团_杰克科技股份_部门", "岗位", "P5", null, false, null);
    }
}
