package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_probe")
public class StoreProbe extends BaseEntity {
    @TableId
    private Long id;
    private String storeId;
    private String storeName;
    private String remarks;
}
