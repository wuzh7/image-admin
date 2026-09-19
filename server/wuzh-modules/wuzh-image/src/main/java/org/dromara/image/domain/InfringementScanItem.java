package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("infringement_scan_item")
public class InfringementScanItem extends BaseEntity {
    @TableId
    private Long id;
    private Long taskId;
    private Long imageId;
    private String status;
    private String riskLevel;
    private String riskReason;
    private String caseNumbersJson;
    private String callType;
    private String imageDescription;
    private String rawAiResponse;
    private String model;
    private String promptVersion;
    private String executionMode;
    private Boolean searchUsed;
    private Integer searchQueryCount;
    private Integer attemptCount;
    private String lastRequestId;
    private String providerJobId;
    private Integer providerRequestIndex;
    private String errorCode;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    @TableField(exist = false)
    private String infringementMode;
    @TableField(exist = false)
    private Long batchId;
}
