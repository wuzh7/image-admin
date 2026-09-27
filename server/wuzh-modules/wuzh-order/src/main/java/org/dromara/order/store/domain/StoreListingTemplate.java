package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_listing_template")
public class StoreListingTemplate extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long shopId;
    private Long categoryId;
    private String name;
    private String ossKey;
    private String originalFilename;
    private String configJson;
    private Integer versionNo;
    private String templateCode;
    private String status;
}
