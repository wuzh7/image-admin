package org.dromara.system.service.impl;

import org.dromara.common.satoken.core.service.SaPermissionImpl;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.api.model.LoginUser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mockStatic;

@Tag("local")
class SaPermissionImplTest {
    @Test
    void staleSessionCannotGrantAnotherUserSuperAdminAccess() {
        LoginUser user = new LoginUser();
        user.setUserType("sys_user");
        user.setUserId(2L);
        user.setRolePermission(Set.of("superadmin", "deptadmin"));
        user.setMenuPermission(Set.of("*:*:*", "image:dedup:query"));
        try (MockedStatic<LoginHelper> login = mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::getLoginUser).thenReturn(user);
            login.when(() -> LoginHelper.isSuperAdmin(2L)).thenCallRealMethod();
            SaPermissionImpl service = new SaPermissionImpl();
            assertEquals(java.util.List.of("deptadmin"), service.getRoleList(user.getLoginId(), ""));
            assertEquals(java.util.List.of("image:dedup:query"), service.getPermissionList(user.getLoginId(), ""));
        }
    }
}
