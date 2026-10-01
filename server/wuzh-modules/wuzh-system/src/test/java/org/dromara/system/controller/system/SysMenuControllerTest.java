package org.dromara.system.controller.system;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.strategy.SaStrategy;
import cn.hutool.extra.spring.SpringUtil;
import org.dromara.common.security.config.SecurityConfig;
import org.dromara.common.security.config.properties.SecurityProperties;
import org.dromara.common.security.handler.AllUrlHandler;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.AntPathMatcher;
import jakarta.servlet.ServletException;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.SysDeptPermissionService;
import org.dromara.system.service.SysDeptRoleService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.SysMenu;
import org.dromara.system.mapper.SysRoleMenuMapper;
import org.dromara.system.service.ISysMenuService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@Tag("local")
class SysMenuControllerTest {
    @Test
    void ordinaryUserKeepsSystemMenusGrantedByPermissions() {
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

        assertThat(systemMenu.getChildren()).hasSize(7);
    }

    @Test
    void ordinaryUserCanUseGrantedSystemPermissionsAndIsDeniedWithoutThem() throws Exception {
        StpInterface original = SaManager.getStpInterface();
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getUserId).thenReturn(2L);
            var permissions = new AtomicReference<>(List.of("system:menu:query", "system:role:query", "system:dept:permission"));
            SaManager.setStpInterface(new StpInterface() {
                @Override public List<String> getPermissionList(Object loginId, String loginType) { return permissions.get(); }
                @Override public List<String> getRoleList(Object loginId, String loginType) { return List.of("ordinary"); }
            });
            ISysMenuService menus = mock(ISysMenuService.class);
            when(menus.selectMenuList(2L)).thenReturn(List.of());
            var mvc = standaloneSetup(new SysMenuController(menus, mock(SysRoleMenuMapper.class)),
                new SysRoleController(mock(ISysRoleService.class)),
                new SysDeptController(mock(ISysDeptService.class), mock(SysDeptRoleService.class), menus,
                    mock(SysDeptPermissionService.class)), new SysUserAuthorizationPageTest.TestLoginController())
                .addInterceptors(new SaInterceptor()).addFilters(new SaTokenContextFilterForJakartaServlet()).build();
            String token = mvc.perform(post("/test-login")).andReturn().getResponse().getContentAsString();
            for (String path : List.of("/system/menu/roleMenuTreeselect/7", "/system/role/7", "/system/dept/permissionTree")) {
                mvc.perform(get(path).header(SaManager.getConfig().getTokenName(), token)).andExpect(status().isOk());
            }
            permissions.set(List.of());
            for (String path : List.of("/system/menu/roleMenuTreeselect/7", "/system/role/7", "/system/dept/permissionTree")) {
                ServletException denied = assertThrows(ServletException.class,
                    () -> mvc.perform(get(path).header(SaManager.getConfig().getTokenName(), token)));
                assertInstanceOf(NotPermissionException.class, denied.getCause());
            }
        } finally {
            SaManager.setStpInterface(original);
        }
    }

    @Test
    void globalInterceptorDoesNotRejectOrdinaryUsersForSystemPaths() throws Exception {
        var routeMatch = SaStrategy.instance.routeMatcher;
        try (MockedStatic<SpringUtil> spring = mockStatic(SpringUtil.class);
             MockedStatic<StpUtil> token = mockStatic(StpUtil.class);
             MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            SaStrategy.instance.routeMatcher = new AntPathMatcher()::match;
            AllUrlHandler urls = new AllUrlHandler();
            urls.setUrls(List.of("/system/**"));
            spring.when(() -> SpringUtil.getBean(AllUrlHandler.class)).thenReturn(urls);
            token.when(() -> StpUtil.getExtra(LoginHelper.CLIENT_KEY)).thenReturn("test-client");
            SecurityProperties properties = new SecurityProperties();
            properties.setExcludes(new String[0]);
            var registry = new InterceptorRegistry() {
                HandlerInterceptor[] interceptors() {
                    return getInterceptors().toArray(HandlerInterceptor[]::new);
                }
            };
            new SecurityConfig(properties).addInterceptors(registry);
            var mvc = standaloneSetup(new TestSystemController())
                .addInterceptors(registry.interceptors()).addFilters(new SaTokenContextFilterForJakartaServlet()).build();
            mvc.perform(get("/system/test-permissions").servletPath("/system/test-permissions")
                .header(LoginHelper.CLIENT_KEY, "test-client")).andExpect(status().isOk());
        } finally {
            SaStrategy.instance.routeMatcher = routeMatch;
        }
    }

    @RestController
    static class TestSystemController {
        @GetMapping("/system/test-permissions")
        String granted() { return "ok"; }
    }

    private static SysMenu menu(String component) {
        SysMenu menu = new SysMenu();
        menu.setComponent(component);
        return menu;
    }
}
