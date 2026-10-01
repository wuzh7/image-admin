package org.dromara.system.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.system.domain.SysDeptMenu;
import org.dromara.system.domain.SysMenu;
import org.dromara.system.domain.SysRoleMenu;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.vo.SysMenuVo;
import org.dromara.system.event.OnlineUserCleanEvent;
import org.dromara.system.mapper.SysDeptMenuMapper;
import org.dromara.system.mapper.SysMenuMapper;
import org.dromara.system.mapper.SysRoleMenuMapper;
import org.dromara.system.mapper.SysUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SysDeptPermissionService {
    private final SysDeptMenuMapper deptMenuMapper;
    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserMapper userMapper;

    public List<Long> menuIds(Long deptId) {
        return deptMenuMapper.selectMenuIds(deptId);
    }

    public void delete(Long deptId) {
        deptMenuMapper.lambda().eq(SysDeptMenu::getDeptId, deptId).delete();
    }

    public Set<Long> menuIdsByUser(Long userId) {
        SysUser user = userMapper.selectById(userId);
        return user == null || user.getDeptId() == null ? Set.of() : new HashSet<>(menuIds(user.getDeptId()));
    }

    public List<SysMenuVo> selectableMenus() {
        return menuMapper.lambda().eq(SysMenu::getStatus, SystemConstants.NORMAL)
            .orderByAsc(SysMenu::getParentId, SysMenu::getOrderNum).voList().stream()
            .filter(menu -> !reserved(menu.getPerms())).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void seedFromRoles(Long deptId, Collection<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return;
        Set<Long> ids = new LinkedHashSet<>();
        roleMenuMapper.lambda().in(SysRoleMenu::getRoleId, roleIds).list()
            .forEach(relation -> ids.add(relation.getMenuId()));
        ids.retainAll(selectableMenus().stream().map(SysMenuVo::getMenuId).toList());
        replace(deptId, ids);
    }

    @Transactional(rollbackFor = Exception.class)
    public void replace(Long deptId, Collection<Long> requestedIds) {
        Set<Long> ids = new LinkedHashSet<>(requestedIds == null ? List.<Long>of() : requestedIds);
        List<SysMenu> menus = ids.isEmpty() ? List.of() : menuMapper.lambda().in(SysMenu::getMenuId, ids).list();
        if (menus.size() != ids.size() || menus.stream().anyMatch(menu -> reserved(menu.getPerms())
            || !SystemConstants.NORMAL.equals(menu.getStatus()))) {
            throw new ServiceException("部门权限包含不存在或仅超级管理员可用的菜单");
        }
        // 子菜单被选中时补全父目录，避免权限已授权但菜单无法呈现。
        while (true) {
            Set<Long> parentIds = new LinkedHashSet<>();
            for (SysMenu menu : menus) {
                if (menu.getParentId() != null && menu.getParentId() != 0 && !ids.contains(menu.getParentId())) {
                    parentIds.add(menu.getParentId());
                }
            }
            if (parentIds.isEmpty()) break;
            List<SysMenu> parents = menuMapper.lambda().in(SysMenu::getMenuId, parentIds).list();
            if (parents.size() != parentIds.size() || parents.stream().anyMatch(menu -> reserved(menu.getPerms())
                || !SystemConstants.NORMAL.equals(menu.getStatus()))) {
                throw new ServiceException("部门权限的上级菜单不可用");
            }
            menus = new java.util.ArrayList<>(menus);
            menus.addAll(parents);
            ids.addAll(parentIds);
        }
        deptMenuMapper.lambda().eq(SysDeptMenu::getDeptId, deptId).delete();
        if (!ids.isEmpty()) {
            deptMenuMapper.insertBatch(ids.stream().map(menuId -> {
                SysDeptMenu relation = new SysDeptMenu();
                relation.setDeptId(deptId);
                relation.setMenuId(menuId);
                return relation;
            }).toList());
        }
        List<Long> userIds = userMapper.lambda().select(SysUser::getUserId)
            .eq(SysUser::getDeptId, deptId).objs(value -> Long.valueOf(value.toString()));
        if (!userIds.isEmpty()) SpringUtils.context().publishEvent(OnlineUserCleanEvent.byUsers(userIds));
    }

    private boolean reserved(String perms) {
        if (perms == null) return false;
        return perms.startsWith("tro:")
            || perms.equals("image:dedup:remove")
            || perms.equals("order:detail:assign");
    }
}
