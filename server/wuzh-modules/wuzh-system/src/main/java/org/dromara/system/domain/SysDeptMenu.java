package org.dromara.system.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("sys_dept_menu")
public class SysDeptMenu {
    @TableId(type = IdType.INPUT)
    private Long deptId;
    private Long menuId;
}
