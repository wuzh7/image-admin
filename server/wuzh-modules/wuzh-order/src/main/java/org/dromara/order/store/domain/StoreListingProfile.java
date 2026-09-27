package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_listing_profile")
public class StoreListingProfile extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long shopId;
    private Long categoryId;
    private String configJson;
}
