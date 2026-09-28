package org.dromara.system.controller.system;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.crypto.digest.BCrypt;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.CacheNames;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.encrypt.annotation.ApiEncrypt;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.helper.DataPermissionHelper;
import org.dromara.common.redis.annotation.RepeatSubmit;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.dromara.system.api.model.LoginUser;
import org.dromara.system.domain.bo.SysRoleBo;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.*;
import org.dromara.system.event.OnlineUserCleanEvent;
import org.dromara.system.service.ISysDeptService;
import org.dromara.system.service.ISysRoleService;
import org.dromara.system.service.ISysUserService;
import org.dromara.system.service.SysDeptRoleService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户信息
 *
 * @author Lion Li
 */
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/system/user")
public class SysUserController extends BaseController {

    private final ISysUserService userService;
    private final ISysRoleService roleService;
    private final ISysDeptService deptService;
    private final SysDeptRoleService deptRoleService;

    /**
     * 分页查询用户列表。
     *
     * @param user      用户查询条件
     * @param pageQuery 分页参数
     * @return 用户分页列表
     */
    @SaCheckPermission("system:user:list")
    @GetMapping("/list")
    public R<PageResult<SysUserVo>> list(SysUserBo user, PageQuery pageQuery) {
        return R.ok(userService.selectPageUserList(user, pageQuery));
    }

    /**
     * 获取用户信息
     *
     * @return 当前登录用户信息、角色与权限集合
     */
    @GetMapping("/getInfo")
    public R<UserInfoVo> getInfo() {
        UserInfoVo userInfoVo = new UserInfoVo();
        LoginUser loginUser = LoginHelper.getLoginUser();

        SysUserVo user = DataPermissionHelper.ignore(() -> userService.selectUserById(loginUser.getUserId()));
        if (ObjectUtil.isNull(user)) {
            return R.fail("没有权限访问用户数据!");
        }
        userInfoVo.setUser(user);
        Set<String> permissions = new HashSet<>(loginUser.getMenuPermission());
        Set<String> roles = new HashSet<>(loginUser.getRolePermission());
        if (!LoginHelper.isSuperAdmin(loginUser.getUserId())) {
            permissions.remove("*:*:*");
            roles.remove(SystemConstants.SUPER_ADMIN_ROLE_KEY);
        }
        userInfoVo.setPermissions(permissions);
        userInfoVo.setRoles(roles);
        return R.ok(userInfoVo);
    }

    /**
     * 根据用户编号获取详细信息
     *
     * @param userId 用户ID
     * @return 用户详情与角色信息
     */
    @SaCheckPermission("system:user:query")
    @GetMapping(value = {"/", "/{userId}"})
    public R<SysUserInfoVo> getInfo(@PathVariable(value = "userId", required = false) Long userId) {
        SysUserInfoVo userInfoVo = new SysUserInfoVo();
        if (ObjectUtil.isNotNull(userId)) {
            userService.checkUserDataScope(userId);
            SysUserVo sysUser = userService.selectUserById(userId);
            userInfoVo.setUser(sysUser);
            userInfoVo.setRoleIds(roleService.selectRoleListByUserId(userId));
        }
        if (LoginHelper.isAdmin()) {
            SysRoleBo roleBo = new SysRoleBo();
            roleBo.setStatus(SystemConstants.NORMAL);
            userInfoVo.setRoles(roleService.selectRoleList(roleBo));
        } else {
            userInfoVo.setRoles(deptRoleService.roles(LoginHelper.getDeptId()));
        }
        return R.ok(userInfoVo);
    }

    /**
     * 新增用户。
     *
     * @param user 用户新增参数
     * @return 操作结果
     */
    @SaCheckPermission("system:user:add")
    @Log(title = "用户管理", businessType = BusinessType.INSERT)
    @RepeatSubmit()
    @PostMapping
    public R<Void> add(@Validated @RequestBody SysUserBo user) {
        checkManagedDept(user.getDeptId());
        deptService.checkDeptDataScope(user.getDeptId());
        deptRoleService.validate(user.getDeptId(), user.getRoleIds() == null ? List.of() : Arrays.asList(user.getRoleIds()));
        user.setUserName(user.getUserName().trim());
        user.setNickName(user.getNickName().trim());
        user.setPhoneNumber(null);
        user.setEmail(null);
        user.setGender("2");
        if (!userService.checkUserNameUnique(user)) {
            return R.fail("新增用户'" + user.getUserName() + "'失败，登录账号已存在");
        }
        if (!userService.checkNickNameUnique(user)) {
            return R.fail("新增用户'" + user.getNickName() + "'失败，用户名称已存在");
        }
        user.setPassword(BCrypt.hashpw(user.getPassword()));
        return toAjax(userService.insertUser(user));
    }

    /**
     * 修改用户。
     *
     * @param user 用户编辑参数
     * @return 操作结果
     */
    @SaCheckPermission("system:user:edit")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping
    public R<Void> edit(@Validated @RequestBody SysUserBo user) {
        if (user.getUserId() == null) return R.fail("用户不存在");
        userService.checkUserAllowed(user.getUserId());
        userService.checkUserDataScope(user.getUserId());
        checkManagedDept(user.getDeptId());
        if (user.getUserId().equals(LoginHelper.getUserId()) && user.getRoleIds() != null
            && !new HashSet<>(Arrays.asList(user.getRoleIds())).equals(
                new HashSet<>(roleService.selectRoleListByUserId(user.getUserId())))) {
            return R.fail("不能修改自己的角色");
        }
        deptService.checkDeptDataScope(user.getDeptId());
        deptRoleService.validate(user.getDeptId(), user.getRoleIds() == null
            ? roleService.selectRoleListByUserId(user.getUserId()) : Arrays.asList(user.getRoleIds()));
        user.setUserName(user.getUserName().trim());
        user.setNickName(user.getNickName().trim());
        user.setPhoneNumber(null);
        user.setEmail(null);
        user.setGender("2");
        if (!userService.checkUserNameUnique(user)) {
            return R.fail("修改用户'" + user.getUserName() + "'失败，登录账号已存在");
        }
        if (!userService.checkNickNameUnique(user)) {
            return R.fail("修改用户'" + user.getNickName() + "'失败，用户名称已存在");
        }
        int rows = userService.updateUser(user);
        if (rows > 0) SpringUtils.context().publishEvent(OnlineUserCleanEvent.byUsers(List.of(user.getUserId())));
        return toAjax(rows);
    }

    /**
     * 删除用户
     *
     * @param userIds 用户ID数组
     * @return 操作结果
     */
    @SaCheckPermission("system:user:remove")
    @Log(title = "用户管理", businessType = BusinessType.DELETE)
    @DeleteMapping("/{userIds}")
    public R<Void> remove(@PathVariable Long[] userIds) {
        if (ArrayUtil.contains(userIds, LoginHelper.getUserId())) {
            return R.fail("当前用户不能删除");
        }
        return toAjax(userService.deleteUserByIds(userIds));
    }

    /**
     * 根据用户ID串批量获取用户基础信息
     *
     * @param userIds 用户ID串
     * @param deptId  部门ID
     * @return 用户基础信息列表
     */
    @SaCheckPermission("system:user:query")
    @GetMapping("/optionselect")
    public R<List<SysUserVo>> optionselect(@RequestParam(required = false) Long[] userIds,
                                           @RequestParam(required = false) Long deptId) {
        return R.ok(userService.selectUserByIds(ArrayUtil.isEmpty(userIds) ? null : List.of(userIds), deptId));
    }

    /**
     * 重置指定用户密码。
     *
     * @param user 用户参数
     * @return 操作结果
     */
    @ApiEncrypt
    @SaCheckPermission("system:user:resetPwd")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/resetPwd")
    public R<Void> resetPwd(@RequestBody SysUserBo user) {
        userService.checkUserAllowed(user.getUserId());
        userService.checkUserDataScope(user.getUserId());
        user.setPassword(BCrypt.hashpw(user.getPassword()));
        int rows = userService.resetUserPwd(user.getUserId(), user.getPassword());
        if (rows > 0) {
            SpringUtils.context().publishEvent(OnlineUserCleanEvent.byUsers(List.of(user.getUserId())));
            return R.ok();
        }
        return R.fail();
    }

    /**
     * 修改用户状态。
     *
     * @param user 用户参数
     * @return 操作结果
     */
    @SaCheckPermission("system:user:edit")
    @Log(title = "用户管理", businessType = BusinessType.UPDATE)
    @RepeatSubmit()
    @PutMapping("/changeStatus")
    public R<Void> changeStatus(@RequestBody SysUserBo user) {
        userService.checkUserAllowed(user.getUserId());
        userService.checkUserDataScope(user.getUserId());
        int rows = userService.updateUserStatus(user.getUserId(), user.getStatus());
        if (rows > 0) SpringUtils.context().publishEvent(OnlineUserCleanEvent.byUsers(List.of(user.getUserId())));
        return toAjax(rows);
    }

    /**
     * 解锁用户
     *
     * @param userId 用户ID
     * @return 操作结果
     */
    @SaCheckPermission("system:user:edit")
    @Log(title = "用户解锁", businessType = BusinessType.OTHER)
    @RepeatSubmit()
    @GetMapping("/unlock/{userId}")
    public R<Void> unlock(@PathVariable Long userId) {
        userService.checkUserDataScope(userId);
        SysUserVo user = userService.selectUserById(userId);
        if (ObjectUtil.isNull(user)) {
            return R.fail("用户不存在");
        }
        String loginName = CacheNames.PWD_ERR_CNT_KEY + user.getUserName();
        if (RedisUtils.hasKey(loginName)) {
            RedisUtils.deleteObject(loginName);
        }
        return R.ok();
    }

    /**
     * 根据用户编号获取授权角色
     *
     * @param userId 用户ID
     * @return 用户及其可授权角色信息
     */
    @SaCheckPermission("system:user:query")
    @GetMapping("/authRole/{userId}")
    public R<SysUserInfoVo> authRole(@PathVariable Long userId) {
        userService.checkUserDataScope(userId);
        SysUserVo user = userService.selectUserById(userId);
        List<Long> assignedRoleIds = roleService.selectRoleListByUserId(userId);
        List<SysRoleVo> roles = deptRoleService.roles(user.getDeptId());
        roles.forEach(role -> role.setFlag(assignedRoleIds.contains(role.getRoleId())));
        SysUserInfoVo userInfoVo = new SysUserInfoVo();
        userInfoVo.setUser(user);
        userInfoVo.setRoles(roles);
        return R.ok(userInfoVo);
    }

    /**
     * 用户授权角色
     *
     * @param userId  用户Id
     * @param roleIds 角色ID串
     * @return 操作结果
     */
    @SaCheckPermission("system:user:edit")
    @Log(title = "用户管理", businessType = BusinessType.GRANT)
    @RepeatSubmit()
    @PutMapping("/authRole")
    public R<Void> insertAuthRole(Long userId, Long[] roleIds) {
        if (userId == null) return R.fail("用户不存在");
        if (userId.equals(LoginHelper.getUserId())) return R.fail("不能修改自己的角色");
        userService.checkUserAllowed(userId);
        userService.checkUserDataScope(userId);
        SysUserVo user = userService.selectUserById(userId);
        deptRoleService.validate(user.getDeptId(), roleIds == null ? List.of() : Arrays.asList(roleIds));
        userService.insertUserAuth(userId, roleIds);
        SpringUtils.context().publishEvent(OnlineUserCleanEvent.byUsers(List.of(userId)));
        return R.ok();
    }

    private void checkManagedDept(Long deptId) {
        if (!LoginHelper.isAdmin() && !ObjectUtil.equal(deptId, LoginHelper.getDeptId())) {
            throw new ServiceException("只能管理本部门用户");
        }
    }

}
