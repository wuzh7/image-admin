package org.dromara.system.controller.system;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.filter.SaTokenContextFilterForJakartaServlet;
import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysUserService;
import org.dromara.system.service.SysDeptRoleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import jakarta.servlet.ServletException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@Tag("local")
class SysUserAuthorizationPageTest {

    @Test
    void userEditorCanLoadRoleAssignmentPage() throws Exception {
        StpInterface original = SaManager.getStpInterface();
        try {
            var permissions = new AtomicReference<>(List.of("system:user:edit"));
            SaManager.setStpInterface(new StpInterface() {
                @Override public List<String> getPermissionList(Object loginId, String loginType) {
                    return permissions.get();
                }
                @Override public List<String> getRoleList(Object loginId, String loginType) {
                    return List.of("admin");
                }
            });
            ISysUserService users = mock(ISysUserService.class);
            ISysRoleService roles = mock(ISysRoleService.class);
            SysDeptRoleService deptRoles = mock(SysDeptRoleService.class);
            SysUserVo user = new SysUserVo();
            user.setDeptId(1L);
            when(users.selectUserById(7L)).thenReturn(user);
            when(roles.selectRoleListByUserId(7L)).thenReturn(List.of());
            when(deptRoles.roles(1L)).thenReturn(List.of());
            var controller = new SysUserController(users, roles, mock(ISysDeptService.class), deptRoles);
            var mvc = standaloneSetup(controller, new TestLoginController()).addInterceptors(new SaInterceptor())
                .addFilters(new SaTokenContextFilterForJakartaServlet()).build();
            String token = mvc.perform(post("/test-login")).andReturn().getResponse().getContentAsString();

            mvc.perform(get("/system/user/authRole/7").header(SaManager.getConfig().getTokenName(), token))
                .andExpect(status().isOk());
            permissions.set(List.of());
            ServletException denied = assertThrows(ServletException.class,
                () -> mvc.perform(get("/system/user/authRole/7").header(SaManager.getConfig().getTokenName(), token)));
            assertInstanceOf(NotPermissionException.class, denied.getCause());
        } finally {
            SaManager.setStpInterface(original);
        }
    }

    @RestController
    static class TestLoginController {
        @PostMapping("/test-login")
        String login() {
            StpUtil.login("role-editor");
            return StpUtil.getTokenValue();
        }
    }
}
