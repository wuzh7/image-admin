package org.dromara.system.service;

import cn.hutool.core.collection.CollUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.system.domain.SysDeptRole;
import org.dromara.system.domain.SysRole;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.mapper.SysDeptRoleMapper;
import org.dromara.system.mapper.SysRoleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysDeptRoleService {
    private final SysDeptRoleMapper deptRoleMapper;
    private final SysRoleMapper roleMapper;

    public List<Long> roleIds(Long deptId) {
        return deptId == null ? List.of() : deptRoleMapper.selectRoleIds(deptId);
    }

    public List<SysRoleVo> roles(Long deptId) {
        List<Long> roleIds = roleIds(deptId);
        if (roleIds.isEmpty()) return List.of();
        return roleMapper.selectVoList(roleMapper.lambda()
            .in(SysRole::getRoleId, roleIds)
            .eq(SysRole::getStatus, SystemConstants.NORMAL)
            .orderByAsc(SysRole::getRoleName)
            .build());
    }

    public void validate(Long deptId, Collection<Long> requestedRoleIds) {
        if (deptId == null) throw new ServiceException("请选择所属部门");
        Set<Long> requested = requestedRoleIds == null ? Set.of() : new LinkedHashSet<>(requestedRoleIds);
        if (requested.isEmpty()) throw new ServiceException("请选择用户角色");
        if (requested.contains(SystemConstants.SUPER_ADMIN_ROLE_ID)) throw new ServiceException("超级管理员不能属于部门");
        if (!new LinkedHashSet<>(roleIds(deptId)).containsAll(requested)) {
            throw new ServiceException("所选角色不属于该部门");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void replace(Long deptId, Collection<Long> roleIds) {
        if (roleIds != null && roleIds.contains(SystemConstants.SUPER_ADMIN_ROLE_ID))
            throw new ServiceException("超级管理员不能属于部门");
        deptRoleMapper.lambda().eq(SysDeptRole::getDeptId, deptId).delete();
        if (CollUtil.isEmpty(roleIds)) return;
        List<SysDeptRole> relations = new LinkedHashSet<>(roleIds).stream().map(roleId -> {
            SysDeptRole relation = new SysDeptRole();
            relation.setDeptId(deptId);
            relation.setRoleId(roleId);
            return relation;
        }).toList();
        long enabledCount = roleMapper.lambda()
            .in(SysRole::getRoleId, relations.stream().map(SysDeptRole::getRoleId).toList())
            .eq(SysRole::getStatus, SystemConstants.NORMAL).count();
        if (enabledCount != relations.size()) throw new ServiceException("部门角色不存在或已停用");
        deptRoleMapper.insertBatch(relations);
    }

    public void delete(Long deptId) {
        deptRoleMapper.lambda().eq(SysDeptRole::getDeptId, deptId).delete();
    }
}
