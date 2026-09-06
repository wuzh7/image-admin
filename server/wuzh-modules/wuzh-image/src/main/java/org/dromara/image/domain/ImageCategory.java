package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("img_category")
public class ImageCategory extends BaseEntity {

    @TableId
    private Long id;
    private String name;
    private Integer sortOrder;
    private String status;
}
