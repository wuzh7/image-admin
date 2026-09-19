package org.dromara.system.service.impl;

import org.dromara.system.domain.bo.SysRoleBo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
class SysRoleServiceImplTest {

    @Test
    @Tag("local")
    void savesRolePermissionWithoutDepartmentAssignments() throws NoSuchMethodException {
        SysRoleServiceImpl service = new SysRoleServiceImpl(null, null, null, null, null);
        SysRoleBo role = new SysRoleBo();
        role.setRoleId(1L);

        Method method = SysRoleServiceImpl.class.getDeclaredMethod("insertRoleDept", SysRoleBo.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> invoke(method, service, role));
    }

    @Test
    @Tag("local")
    void savesRolePermissionWithoutMenuAssignments() throws NoSuchMethodException {
        SysRoleServiceImpl service = new SysRoleServiceImpl(null, null, null, null, null);
        SysRoleBo role = new SysRoleBo();
        role.setRoleId(1L);

        Method method = SysRoleServiceImpl.class.getDeclaredMethod("insertRoleMenu", SysRoleBo.class);
        method.setAccessible(true);

        assertDoesNotThrow(() -> invoke(method, service, role));
    }

    private void invoke(Method method, SysRoleServiceImpl service, SysRoleBo role) throws Throwable {
        try {
            method.invoke(service, role);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
    }
}
