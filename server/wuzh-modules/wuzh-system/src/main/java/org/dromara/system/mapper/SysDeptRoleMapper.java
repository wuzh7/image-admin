package org.dromara.system.mapper;

import cn.hutool.core.convert.Convert;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.system.domain.SysDeptRole;

import java.util.List;

public interface SysDeptRoleMapper extends BaseMapperPlus<SysDeptRole, SysDeptRole> {
    default List<Long> selectRoleIds(Long deptId) {
        return lambda().select(SysDeptRole::getRoleId)
            .eq(SysDeptRole::getDeptId, deptId)
            .objs(Convert::toLong);
    }
}
