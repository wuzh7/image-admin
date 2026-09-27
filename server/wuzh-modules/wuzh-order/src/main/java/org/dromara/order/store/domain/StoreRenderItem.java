package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_render_item")
public class StoreRenderItem extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long batchId;
    private Long templateGroupId;
    private Long templateId;
    private Long imageId;
    private String renderConfigHash;
    private String calibrationConfigJson;
    private String maskOssKey;
    private String templateConfigJson;
    private String status;
    private LocalDateTime leaseUntil;
    private String leaseToken;
    private String workerId;
    private String outputFormat;
    private Integer quality;
    private String resultOssKey;
    private String resultUrl;
    private String resultSha256;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
