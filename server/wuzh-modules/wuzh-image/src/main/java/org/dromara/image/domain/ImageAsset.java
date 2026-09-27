package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("img_asset")
public class ImageAsset extends BaseEntity {

    @TableId
    private Long id;
    private Long batchId;
    private Long categoryId;
    private String storageKey;
    private String thumbnailKey;
    private String cleanupKey;
    private String cleanupThumbnailKey;
    private Long infringementScanItemId;
    private String originalName;
    private String displayName;
    private String contentType;
    private Long fileSize;
    private Integer width;
    private Integer height;
    private String aspectRatio;
    private String sha256;
    private String status;
    private Boolean duplicateFlag;
    private Long duplicateOf;
    private BigDecimal similarity;
    private String similarMatchesJson;
    private String matchMethod;
    private byte[] globalVector;
    private byte[] centerVector;
    private byte[] queryVectors;
    private Boolean manuallyApproved;
    private Long replacesImageId;
    private String featureVersion;
    private String errorMessage;
    private Integer renderUseCount;
    private LocalDateTime approvedAt;
    private LocalDateTime deletedAt;
    @TableLogic
    private String delFlag;
}
