package org.dromara.system.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.mapper.SysDeptRoleMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import org.dromara.common.core.constant.SystemConstants;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("dev"), @Tag("local")})
class SysDeptRoleServiceTest {
    @Mock
    private SysDeptRoleMapper deptRoleMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;

    @Test
    void rejectsSuperAdminAsDepartmentRole() {
        SysDeptRoleService service = new SysDeptRoleService(deptRoleMapper, roleMapper, userRoleMapper);
        assertThatThrownBy(() -> service.validate(10L, List.of(SystemConstants.SUPER_ADMIN_ROLE_ID)))
            .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> service.replace(10L, List.of(SystemConstants.SUPER_ADMIN_ROLE_ID)))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    void rejectsRolesOutsideTheUsersDepartment() {
        when(deptRoleMapper.selectRoleIds(10L)).thenReturn(List.of(1L, 2L));
        SysDeptRoleService service = new SysDeptRoleService(deptRoleMapper, roleMapper, userRoleMapper);

        assertThatThrownBy(() -> service.validate(10L, List.of(2L, 3L)))
            .isInstanceOf(ServiceException.class)
            .hasMessage("所选角色不属于该部门");
    }

    @Test
    void rejectsRemovingRoleStillAssignedToDepartmentUser() {
        when(userRoleMapper.selectAssignedRoleIdsByDeptId(10L)).thenReturn(List.of(2L));
        SysDeptRoleService service = new SysDeptRoleService(deptRoleMapper, roleMapper, userRoleMapper);
        assertThatThrownBy(() -> service.replace(10L, List.of(1L)))
            .isInstanceOf(ServiceException.class)
            .hasMessage("请先调整部门用户的角色，再移除部门角色");
    }
}
