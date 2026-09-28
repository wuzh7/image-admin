package org.dromara.system.mapper;

import cn.hutool.core.convert.Convert;
import org.dromara.common.mybatis.core.mapper.BaseMapperPlus;
import org.dromara.system.domain.SysDeptMenu;

import java.util.List;

public interface SysDeptMenuMapper extends BaseMapperPlus<SysDeptMenu, SysDeptMenu> {
    default List<Long> selectMenuIds(Long deptId) {
        return lambda().select(SysDeptMenu::getMenuId).eq(SysDeptMenu::getDeptId, deptId).objs(Convert::toLong);
    }
}
