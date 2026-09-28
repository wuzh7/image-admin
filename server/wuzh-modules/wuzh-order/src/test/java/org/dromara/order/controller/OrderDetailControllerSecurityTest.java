package org.dromara.order.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import org.dromara.common.core.constant.SystemConstants;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
class OrderDetailControllerSecurityTest {

    @Test
    void checksEachActionAndRestrictsAllOrderDetailsToSuperAdministrator() {
        Map<String, String> permissions = Map.of(
            "list", "order:detail:list", "getInfo", "order:detail:query",
            "add", "order:detail:add", "edit", "order:detail:edit",
            "assignOwner", "order:detail:assign", "remove", "order:detail:remove",
            "export", "order:detail:export");
        assertThat(OrderDetailController.class.getAnnotation(SaCheckRole.class).value())
            .containsExactly(SystemConstants.SUPER_ADMIN_ROLE_KEY);
        for (Method method : OrderDetailController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            assertThat(permissions).containsKey(method.getName());
            assertThat(method.getAnnotation(SaCheckPermission.class).value())
                .containsExactly(permissions.get(method.getName()));
            SaCheckRole roleCheck = method.getAnnotation(SaCheckRole.class);
            if (method.getName().equals("assignOwner")) {
                assertThat(roleCheck.value()).containsExactly(SystemConstants.SUPER_ADMIN_ROLE_KEY);
            } else {
                assertThat(roleCheck).isNull();
            }
        }
    }
}
