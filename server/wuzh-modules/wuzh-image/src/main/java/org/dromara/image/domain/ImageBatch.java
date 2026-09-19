package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("img_batch")
public class ImageBatch extends BaseEntity {

    @TableId
    private Long id;
    private String name;
    private Long categoryId;
    private String status;
    private String infringementMode;
    private BigDecimal similarityThreshold;
    private Integer totalCount;
    private Integer processedCount;
    private Integer duplicateCount;
    private Integer failedCount;
    private LocalDateTime detectionCompletedAt;
    private String errorMessage;
}
