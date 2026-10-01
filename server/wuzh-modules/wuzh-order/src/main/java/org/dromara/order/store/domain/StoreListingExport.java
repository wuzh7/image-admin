package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_listing_export")
public class StoreListingExport extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long batchId;
    private Long shopId;
    private Long listingTemplateId;
    private String listingProfileJson;
    private String listingKeywordsJson;
    private String imageIdsJson;
    private String idempotencyKey;
    private String status;
    private String ossKey;
    private String errorMessage;
}
