package org.dromara.system.service.impl;

import org.dromara.system.domain.SysMenu;
import org.dromara.system.mapper.SysMenuMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysRoleMenuMapper;
import org.dromara.system.service.SysDeptPermissionService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SysMenuServiceImplTest {
    @Test
    void permissionRequiresBothRoleAndDepartmentMenuId() {
        SysMenuMapper menuMapper = mock(SysMenuMapper.class);
        SysDeptPermissionService deptPermissions = mock(SysDeptPermissionService.class);
        SysMenu allowed = new SysMenu();
        allowed.setMenuId(10L);
        allowed.setPerms("image:dedup:query");
        SysMenu denied = new SysMenu();
        denied.setMenuId(20L);
        denied.setPerms("store:render:add");
        when(menuMapper.selectPermittedMenusByUserId(1L)).thenReturn(List.of(allowed, denied));
        when(deptPermissions.menuIdsByUser(1L)).thenReturn(Set.of(10L));

        SysMenuServiceImpl service = new SysMenuServiceImpl(menuMapper, mock(SysRoleMapper.class),
            mock(SysRoleMenuMapper.class), deptPermissions);

        assertThat(service.selectMenuPermsByUserId(1L)).containsExactly("image:dedup:query");
    }
}
