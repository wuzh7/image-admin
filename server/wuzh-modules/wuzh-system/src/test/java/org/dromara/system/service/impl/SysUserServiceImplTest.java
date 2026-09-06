package org.dromara.system.service.impl;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.exception.ServiceException;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private SysUserServiceImpl service(SysUserMapper userMapper, SysRoleMapper roleMapper,
                                       SysUserRoleMapper userRoleMapper) {
        return new SysUserServiceImpl(userMapper, mock(SysDeptMapper.class), roleMapper,
            mock(SysPostMapper.class), userRoleMapper, mock(SysUserPostMapper.class));
    }
}
