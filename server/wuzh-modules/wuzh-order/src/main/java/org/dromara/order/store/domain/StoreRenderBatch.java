package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_render_batch")
public class StoreRenderBatch extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long categoryId;
    private Long templateGroupId;
    private Long shopId;
    private Long listingTemplateId;
    private String status;
    private Integer selectedCount;
    private Integer completedCount;
    private Integer skippedCount;
    private Integer failedCount;
    private String listingProfileJson;
    private String idempotencyKey;
}
