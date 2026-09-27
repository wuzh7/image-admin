package org.dromara.system.service.impl;

import org.dromara.system.service.ISysMenuService;
import org.dromara.system.service.ISysRoleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("local")
class SysPermissionServiceImplTest {
    @Test
    void onlyFixedAdminUserReceivesSuperAdminRole() {
        ISysRoleService roles = mock(ISysRoleService.class);
        when(roles.selectRolePermissionByUserId(2L)).thenReturn(Set.of("superadmin", "deptadmin"));
        SysPermissionServiceImpl service = new SysPermissionServiceImpl(roles, mock(ISysMenuService.class));

        assertEquals(Set.of("deptadmin"), service.getRolePermission(2L));
        assertEquals(Set.of("superadmin"), service.getRolePermission(1761100000000000001L));
    }
}
