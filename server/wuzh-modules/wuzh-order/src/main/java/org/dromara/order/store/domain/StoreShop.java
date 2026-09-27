package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_shop")
public class StoreShop extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private String shopCode;
    private String name;
    private String platform;
    private String status;
    private String configJson;
}
