package org.dromara.system.service;

import org.dromara.common.mybatis.core.mapper.LambdaCrudChainWrapper;
import org.dromara.system.domain.SysMenu;
import org.dromara.system.domain.vo.SysMenuVo;
import org.dromara.system.mapper.SysDeptMenuMapper;
import org.dromara.system.mapper.SysMenuMapper;
import org.dromara.system.mapper.SysRoleMenuMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@Tag("local")
class SysDeptPermissionServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    void systemManagementPermissionsAreSelectable() {
        SysMenuMapper mapper = mock(SysMenuMapper.class);
        LambdaCrudChainWrapper<SysMenu, SysMenuVo> query = mock(LambdaCrudChainWrapper.class, RETURNS_SELF);
        when(mapper.lambda()).thenReturn(query);
        when(query.eq(any(), any())).thenReturn(query);
        List<SysMenuVo> menus = List.of("system:role:list", "system:menu:query", "system:dept:permission",
            "system:store-probe:list", "image:category:add", "store:reference:list").stream().map(perm -> {
                SysMenuVo menu = new SysMenuVo();
                menu.setPerms(perm);
                return menu;
            }).toList();
        when(query.voList()).thenReturn(menus);
        var service = new SysDeptPermissionService(mock(SysDeptMenuMapper.class), mapper,
            mock(SysRoleMenuMapper.class), mock(SysUserMapper.class));
        assertThat(service.selectableMenus()).containsExactlyElementsOf(menus);
    }
}
