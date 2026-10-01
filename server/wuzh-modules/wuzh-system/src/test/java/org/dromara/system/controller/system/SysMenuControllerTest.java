package org.dromara.system.controller.system;

import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.SysMenu;
import org.dromara.system.mapper.SysRoleMenuMapper;
import org.dromara.system.service.ISysMenuService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Tag("local")
class SysMenuControllerTest {
    @Test
    void ordinaryUserRoutersExcludeSuperAdminConfiguration() {
        ISysMenuService menuService = mock(ISysMenuService.class);
        SysMenu userMenu = menu("system/user/index");
        SysMenu systemMenu = menu(null);
        systemMenu.setChildren(new ArrayList<>(List.of(userMenu,
            menu("system/role/index"), menu("system/menu/index"), menu("system/dept/index"),
            menu("system/store-probe/index"), menu("image/category/index"), menu("store/reference/index"))));
        List<SysMenu> menus = new ArrayList<>(List.of(systemMenu));
        when(menuService.selectMenuTreeByUserId(2L)).thenReturn(menus);

        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            new SysMenuController(menuService, mock(SysRoleMenuMapper.class)).getRouters();
        }

        assertThat(systemMenu.getChildren()).containsExactly(userMenu);
    }

    private static SysMenu menu(String component) {
        SysMenu menu = new SysMenu();
        menu.setComponent(component);
        return menu;
    }
}
