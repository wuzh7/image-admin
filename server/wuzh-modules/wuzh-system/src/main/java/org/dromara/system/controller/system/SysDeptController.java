package org.dromara.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaCheckRole;
import cn.hutool.core.lang.tree.Tree;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.domain.bo.SysDeptBo;
import org.dromara.system.domain.vo.SysDeptVo;
import org.dromara.system.domain.vo.SysRoleVo;
import org.dromara.system.service.ISysMenuService;
import org.dromara.system.service.SysDeptPermissionService;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.SysDeptRoleService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/dept")
public class SysDeptController extends BaseController {
    private final ISysDeptService deptService;
    private final SysDeptRoleService deptRoleService;
    private final ISysMenuService menuService;
    private final SysDeptPermissionService deptPermissionService;

    @SaCheckRole("superadmin")
    @SaCheckPermission("system:dept:permission")
    @GetMapping("/permissionTree")
    public R<List<Tree<Long>>> permissionTree() {
        return R.ok(menuService.buildMenuTreeSelect(deptPermissionService.selectableMenus()));
    }

    @SaCheckRole("superadmin")
    @SaCheckPermission("system:dept:permission")
    @GetMapping("/{deptId}/permissions")
    public R<List<Long>> permissions(@PathVariable Long deptId) {
        if (deptService.selectDeptById(deptId) == null) return R.fail("部门不存在");
        return R.ok(deptPermissionService.menuIds(deptId));
    }

    @SaCheckRole("superadmin")
    @SaCheckPermission("system:dept:permission")
    @Log(title = "部门权限", businessType = BusinessType.GRANT)
    @PutMapping("/{deptId}/permissions")
    public R<Void> updatePermissions(@PathVariable Long deptId, @RequestBody List<Long> menuIds) {
        if (deptService.selectDeptById(deptId) == null) return R.fail("部门不存在");
        deptPermissionService.replace(deptId, menuIds);
        return R.ok();
    }

    @SaCheckPermission("system:dept:list")
    @GetMapping("/list")
    public R<List<SysDeptVo>> list(SysDeptBo bo) {
        if (!LoginHelper.isAdmin()) {
            if (LoginHelper.getDeptId() == null) return R.ok(List.of());
            bo.setDeptId(LoginHelper.getDeptId());
        }
        return R.ok(deptService.selectDeptList(bo));
    }

    @SaCheckPermission("system:dept:query")
    @GetMapping("/treeselect")
    public R<List<Tree<Long>>> treeselect(SysDeptBo bo) {
        if (!LoginHelper.isAdmin()) {
            if (LoginHelper.getDeptId() == null) return R.ok(List.of());
            bo.setDeptId(LoginHelper.getDeptId());
        }
        return R.ok(deptService.selectDeptTreeList(bo));
    }

    @SaCheckPermission("system:dept:query")
    @GetMapping("/{deptId}")
    public R<SysDeptVo> getInfo(@PathVariable Long deptId) {
        if (!LoginHelper.isAdmin() && !deptId.equals(LoginHelper.getDeptId())) return R.fail("只能查看本部门");
        deptService.checkDeptDataScope(deptId);
        return R.ok(deptService.selectDeptById(deptId));
    }

    @SaCheckPermission("system:dept:query")
    @GetMapping("/{deptId}/roles")
    public R<List<SysRoleVo>> roles(@PathVariable Long deptId) {
        if (!LoginHelper.isAdmin() && !deptId.equals(LoginHelper.getDeptId())) return R.fail("只能查看本部门角色");
        deptService.checkDeptDataScope(deptId);
        return R.ok(deptRoleService.roles(deptId));
    }

    @SaCheckPermission("system:dept:add")
    @SaCheckRole("superadmin")
    @Log(title = "部门管理", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping
    public R<Void> add(@Validated @RequestBody SysDeptBo bo) {
        bo.setDeptName(bo.getDeptName().trim());
        return toAjax(deptService.insertDept(bo));
    }

    @SaCheckPermission("system:dept:edit")
    @SaCheckRole("superadmin")
    @Log(title = "部门管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PutMapping
    public R<Void> edit(@Validated @RequestBody SysDeptBo bo) {
        if (bo.getDeptId() == null) return R.fail("部门不存在");
        if (bo.getParentId() == null) return R.fail("修改部门时请选择已有的上级部门");
        if (bo.getDeptId().equals(bo.getParentId())) return R.fail("上级部门不能选择自己");
        bo.setDeptName(bo.getDeptName().trim());
        if (!deptService.checkDeptNameUnique(bo)) return R.fail("修改部门失败，部门名称已存在");
        return toAjax(deptService.updateDept(bo));
    }

    @SaCheckPermission("system:dept:remove")
    @SaCheckRole("superadmin")
    @Log(title = "部门管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{deptId}")
    public R<Void> remove(@PathVariable Long deptId) {
        if (deptService.hasChildByDeptId(deptId)) return R.fail("存在下级部门，不允许删除");
        if (deptService.checkDeptExistUser(deptId)) return R.fail("部门存在用户，不允许删除");
        return toAjax(deptService.deleteDeptById(deptId));
    }
}
