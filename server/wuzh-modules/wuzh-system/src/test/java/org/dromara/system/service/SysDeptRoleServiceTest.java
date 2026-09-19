package org.dromara.system.service;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.mapper.SysDeptRoleMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tags({@Tag("dev"), @Tag("local")})
class SysDeptRoleServiceTest {
    @Mock
    private SysDeptRoleMapper deptRoleMapper;
    @Mock
    private SysRoleMapper roleMapper;

    @Test
    void rejectsRolesOutsideTheUsersDepartment() {
        when(deptRoleMapper.selectRoleIds(10L)).thenReturn(List.of(1L, 2L));
        SysDeptRoleService service = new SysDeptRoleService(deptRoleMapper, roleMapper);

        assertThatThrownBy(() -> service.validate(10L, List.of(2L, 3L)))
            .isInstanceOf(ServiceException.class)
            .hasMessage("所选角色不属于该部门");
    }
}
