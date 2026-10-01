package org.dromara.system.service.impl;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.SysUserRole;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysPostMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.mapper.SysUserPostMapper;
import org.dromara.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class SysUserServiceImplTest {

    @Test
    void cannotGrantSuperAdminToAnotherUser() {
        SysUserServiceImpl service = service(mock(SysUserMapper.class), mock(SysRoleMapper.class), mock(SysUserRoleMapper.class));
        assertThrows(ServiceException.class, () -> service.insertUserAuth(2L,
            new Long[]{SystemConstants.SUPER_ADMIN_ROLE_ID}));
    }

    @Test
    void registerUserAssignsDefaultImageUserRole() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        SysUserServiceImpl service = service(userMapper, roleMapper, userRoleMapper);
        SysRole role = new SysRole();
        role.setRoleId(200L);
        when(roleMapper.selectByRoleKey(SystemConstants.DEFAULT_REGISTER_ROLE_KEY)).thenReturn(role);
        when(userMapper.insert(any(SysUser.class))).thenAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setUserId(100L);
            return 1;
        });
        when(userRoleMapper.insert(any(SysUserRole.class))).thenReturn(1);

        SysUserBo user = new SysUserBo();
        user.setUserName("registered-user");

        assertTrue(service.registerUser(user));
        assertEquals(100L, user.getUserId());
        ArgumentCaptor<SysUserRole> captor = ArgumentCaptor.forClass(SysUserRole.class);
        verify(userRoleMapper).insert(captor.capture());
        assertEquals(100L, captor.getValue().getUserId());
        assertEquals(200L, captor.getValue().getRoleId());
        ArgumentCaptor<SysUser> savedUser = ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(savedUser.capture());
        assertTrue(savedUser.getValue().getRiskScanEnabled());
        assertTrue(savedUser.getValue().getRelayAnalysisEnabled());
        assertFalse(savedUser.getValue().getRenderAllRisksEnabled());
        assertFalse(savedUser.getValue().getListingAllRisksEnabled());
    }

    @Test
    void registerUserFailsWhenDefaultRoleIsMissing() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysUserRoleMapper userRoleMapper = mock(SysUserRoleMapper.class);
        SysUserServiceImpl service = service(userMapper, roleMapper, userRoleMapper);
        when(roleMapper.selectByRoleKey(SystemConstants.DEFAULT_REGISTER_ROLE_KEY)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.registerUser(new SysUserBo()));
        verify(userMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void nonAdminCannotConfigureImagePolicyForNewUser() {
        SysUser user = new SysUser();
        user.setRiskScanEnabled(false);
        user.setRelayAnalysisEnabled(false);
        user.setRenderAllRisksEnabled(true);
        user.setListingAllRisksEnabled(true);
        SysUserServiceImpl.applyImagePolicy(user, false, true);
        assertTrue(user.getRiskScanEnabled());
        assertTrue(user.getRelayAnalysisEnabled());
        assertFalse(user.getRenderAllRisksEnabled());
        assertFalse(user.getListingAllRisksEnabled());
    }

    @Test
    void adminDefaultsAllRiskPermissionsOffButCanEnableThem() {
        SysUser user = new SysUser();
        SysUserServiceImpl.applyImagePolicy(user, true, true);
        assertTrue(user.getRiskScanEnabled());
        assertTrue(user.getRelayAnalysisEnabled());
        assertFalse(user.getRenderAllRisksEnabled());
        assertFalse(user.getListingAllRisksEnabled());
        user.setRenderAllRisksEnabled(true);
        user.setListingAllRisksEnabled(true);
        SysUserServiceImpl.applyImagePolicy(user, true, true);
        assertTrue(user.getRenderAllRisksEnabled());
        assertTrue(user.getListingAllRisksEnabled());
    }

    @Test
    void nonAdminCannotChangeImageProcessingOnEdit() {
        SysUser user = new SysUser();
        user.setRiskScanEnabled(false);
        user.setRelayAnalysisEnabled(false);
        user.setRenderAllRisksEnabled(false);
        user.setListingAllRisksEnabled(false);
        SysUserServiceImpl.applyImagePolicy(user, false, false);
        assertEquals(null, user.getRiskScanEnabled());
        assertEquals(null, user.getRelayAnalysisEnabled());
        assertEquals(null, user.getRenderAllRisksEnabled());
        assertEquals(null, user.getListingAllRisksEnabled());
    }

    @Test
    void departmentManagerCannotManageAnotherDepartmentUser() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysUser target = new SysUser();
        target.setDeptId(200L);
        when(userMapper.selectById(9L)).thenReturn(target);
        try (MockedStatic<LoginHelper> login = org.mockito.Mockito.mockStatic(LoginHelper.class)) {
            login.when(LoginHelper::isAdmin).thenReturn(false);
            login.when(LoginHelper::getDeptId).thenReturn(100L);
            assertThrows(ServiceException.class, () -> service(userMapper, mock(SysRoleMapper.class),
                mock(SysUserRoleMapper.class)).checkUserDataScope(9L));
        }
    }

    private SysUserServiceImpl service(SysUserMapper userMapper, SysRoleMapper roleMapper,
                                       SysUserRoleMapper userRoleMapper) {
        return new SysUserServiceImpl(userMapper, mock(SysDeptMapper.class), roleMapper,
            mock(SysPostMapper.class), userRoleMapper, mock(SysUserPostMapper.class));
    }
}
