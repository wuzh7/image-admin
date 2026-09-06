package org.dromara.order.controller;

import cn.dev33.satoken.annotation.SaCheckRole;
import org.dromara.common.core.constant.SystemConstants;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
class OrderDetailControllerSecurityTest {

    @Test
    void restrictsAllAdminEndpointsToSuperAdministrator() {
        SaCheckRole roleCheck = OrderDetailController.class.getAnnotation(SaCheckRole.class);

        assertThat(roleCheck).isNotNull();
        assertThat(roleCheck.value()).containsExactly(SystemConstants.SUPER_ADMIN_ROLE_KEY);
    }
}
