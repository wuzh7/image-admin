package org.dromara.system.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.mapper.SysDeptMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.dromara.system.service.SysDeptPermissionService;
import org.dromara.system.service.SysDeptRoleService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("local")
class SysDeptServiceImplTest {

    @Test
    void rejectsAddingDepartmentWithoutParentChoiceOrNewCompanyName() {
        SysDeptMapper mapper = mock(SysDeptMapper.class);
        SysDeptBo dept = new SysDeptBo();
        dept.setDeptName("测试部门");

        assertThatThrownBy(() -> service(mapper).insertDept(dept))
            .isInstanceOf(ServiceException.class)
            .hasMessage("请选择上级部门或输入新上级公司名称");
        verify(mapper, never()).insert(org.mockito.ArgumentMatchers.any(SysDept.class));
    }

    @Test
    void rejectsMovingDepartmentToMissingParent() {
        SysDeptMapper mapper = mock(SysDeptMapper.class);
        SysDept oldDept = new SysDept();
        oldDept.setDeptId(11L);
        oldDept.setParentId(10L);
        when(mapper.selectById(11L)).thenReturn(oldDept);
        SysDeptBo change = new SysDeptBo();
        change.setDeptId(11L);
        change.setParentId(99L);

        assertThatThrownBy(() -> service(mapper).updateDept(change))
            .isInstanceOf(ServiceException.class)
            .hasMessage("父部门不存在");
        verify(mapper, never()).updateById(org.mockito.ArgumentMatchers.any(SysDept.class));
    }

    private SysDeptServiceImpl service(SysDeptMapper mapper) {
        return new SysDeptServiceImpl(mapper, mock(SysRoleMapper.class), mock(SysUserMapper.class),
            mock(SysDeptRoleService.class), mock(SysDeptPermissionService.class));
    }
}
