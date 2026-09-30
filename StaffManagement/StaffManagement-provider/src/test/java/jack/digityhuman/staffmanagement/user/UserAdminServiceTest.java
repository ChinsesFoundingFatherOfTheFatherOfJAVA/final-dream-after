package jack.digityhuman.staffmanagement.user;

import jack.digityhuman.staffmanagement.common.BizException;
import jack.digityhuman.staffmanagement.config.AuthProperties;
import jack.digityhuman.staffmanagement.department.service.DepartmentService;
import jack.digityhuman.staffmanagement.entity.SysUser;
import jack.digityhuman.staffmanagement.mapper.ProjectStudentCascadeMapper;
import jack.digityhuman.staffmanagement.mapper.SysRoleMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserMapper;
import jack.digityhuman.staffmanagement.mapper.SysUserRoleMapper;
import jack.digityhuman.staffmanagement.mapper.TrainingProjectCascadeMapper;
import jack.digityhuman.staffmanagement.user.service.UserAdminService;
import jack.digityhuman.staffmanagement.user.service.impl.UserAdminServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    @Test
    void 手动删除_项目负责人保护返回40905() {
        Fixture fixture = fixture();
        when(fixture.cascadeMapper.countProtectedProjects("4001")).thenReturn(2);

        BizException exception = assertThrows(BizException.class,
                () -> fixture.service.delete("1"));

        assertEquals(40905, exception.getCode());
        verify(fixture.userMapper, never()).deleteById(any(Serializable.class));
    }

    @Test
    void 同步删除_项目负责人保护返回PROTECTED() {
        Fixture fixture = fixture();
        when(fixture.cascadeMapper.countProtectedProjects("4001")).thenReturn(1);

        UserAdminService.DeleteOutcome outcome = fixture.service.deleteSyncedByAccount("4001");

        assertEquals(UserAdminService.DeleteOutcome.PROTECTED, outcome);
        verify(fixture.userMapper, never()).deleteById(any(Serializable.class));
    }

    private static Fixture fixture() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        TrainingProjectCascadeMapper cascadeMapper = mock(TrainingProjectCascadeMapper.class);
        ProjectStudentCascadeMapper studentCascadeMapper = mock(ProjectStudentCascadeMapper.class);
        DepartmentService departmentService = mock(DepartmentService.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setAccount("4001");
        user.setSource(UserAdminService.SOURCE_ORACLE);
        when(userMapper.selectById(1L)).thenReturn(user);
        when(userMapper.selectOne(any())).thenReturn(user);
        UserAdminService service = new UserAdminServiceImpl(
                userMapper, userRoleMapper, roleMapper, cascadeMapper, studentCascadeMapper,
                departmentService, passwordEncoder, new AuthProperties());
        return new Fixture(service, userMapper, cascadeMapper);
    }

    private record Fixture(UserAdminService service, SysUserMapper userMapper,
                           TrainingProjectCascadeMapper cascadeMapper) {
    }
}
