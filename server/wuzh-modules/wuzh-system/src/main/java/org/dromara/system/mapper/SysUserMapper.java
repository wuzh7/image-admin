package org.dromara.system.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.base.MPJBaseMapper;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import org.apache.ibatis.annotations.Param;
import org.dromara.common.mybatis.annotation.DataColumn;
import org.dromara.common.mybatis.annotation.DataPermission;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.common.mybatis.core.query.QueryBuilder;
import org.dromara.system.domain.SysDept;
import org.dromara.system.domain.SysUser;
import org.dromara.system.domain.bo.SysUserBo;
import org.dromara.system.domain.vo.SysUserExportVo;
import org.dromara.system.domain.vo.SysUserVo;

import java.util.List;

/**
 * 用户表 数据层
 *
 * @author Lion Li
 */
public interface SysUserMapper extends BaseMapperPlus<SysUser, SysUserVo>, MPJBaseMapper<SysUser> {

    /**
     * 分页查询用户列表，并进行数据权限控制
     *
     * @param page         分页参数
     * @param queryWrapper 查询条件
     * @return 分页的用户信息
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default Page<SysUserVo> selectPageUserList(Page<SysUser> page, Wrapper<SysUser> queryWrapper) {
        return this.selectVoPage(page, queryWrapper);
    }

    /**
     * 查询用户列表，并进行数据权限控制
     *
     * @param queryWrapper 查询条件
     * @return 用户信息集合
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default List<SysUserVo> selectUserList(Wrapper<SysUser> queryWrapper) {
        return this.selectVoList(queryWrapper);
    }

    /**
     * 根据条件分页查询用户列表
     *
     * @param user    查询条件
     * @param deptIds 部门ID集合
     * @return 用户信息集合信息
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "d.dept_id"),
        @DataColumn(key = "userName", value = "u.create_by")
    })
    default List<SysUserExportVo> selectUserExportList(SysUserBo user, List<Long> deptIds) {
        MPJLambdaWrapper<SysUser> wrapper = QueryBuilder.lambdaJoin("u", SysUser.class)
            .selectAll(SysUser.class)
            .selectAs("u1", SysUser::getUserName, SysUserExportVo::getLeaderName)
            .leftJoin(SysDept.class, "d", SysDept::getDeptId, SysUser::getDeptId)
            .leftJoin(SysUser.class, "u1", SysUser::getUserId, SysDept::getLeader)
            .likeIfText("u", SysUser::getUserName, user.getUserName())
            .likeIfText("u", SysUser::getNickName, user.getNickName())
            .eqIfText("u", SysUser::getStatus, user.getStatus())
            .likeIfText("u", SysUser::getPhoneNumber, user.getPhoneNumber())
            .betweenParams("u", SysUser::getCreateTime, user.getParams(), "beginTime", "endTime")
            .inIfNotEmpty("u", SysUser::getDeptId, deptIds)
            .orderByAsc("u", SysUser::getUserId)
            .build();
        return this.selectJoinList(SysUserExportVo.class, wrapper);
    }

    /**
     * 根据用户ID统计用户数量
     *
     * @param userId 用户ID
     * @return 用户数量
     */
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    default long countUserById(Long userId) {
        return lambda().eq(SysUser::getUserId, userId).count();
    }

    /**
     * 根据条件更新用户数据
     *
     * @param user          要更新的用户实体
     * @param updateWrapper 更新条件封装器
     * @return 更新操作影响的行数
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    int update(@Param(Constants.ENTITY) SysUser user, @Param(Constants.WRAPPER) Wrapper<SysUser> updateWrapper);

    /**
     * 根据用户ID更新用户数据
     *
     * @param user 要更新的用户实体
     * @return 更新操作影响的行数
     */
    @Override
    @DataPermission({
        @DataColumn(key = "deptName", value = "dept_id"),
        @DataColumn(key = "userName", value = "create_by")
    })
    int updateById(@Param(Constants.ENTITY) SysUser user);

}
