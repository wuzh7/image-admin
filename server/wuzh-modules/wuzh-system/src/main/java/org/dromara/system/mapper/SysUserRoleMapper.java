package org.dromara.system.mapper;

import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.system.domain.SysUserRole;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 用户与角色关联表 数据层
 *
 * @author Lion Li
 */
public interface SysUserRoleMapper extends BaseMapperPlus<SysUserRole, SysUserRole> {

    @Select("SELECT DISTINCT ur.role_id FROM sys_user_role ur JOIN sys_user u ON u.user_id = ur.user_id WHERE u.dept_id = #{deptId} AND u.del_flag = '0'")
    List<Long> selectAssignedRoleIdsByDeptId(Long deptId);

}
