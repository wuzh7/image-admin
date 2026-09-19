package org.dromara.image.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("infringement_scan_task")
public class InfringementScanTask extends BaseEntity {
    @TableId
    private Long id;
    private String taskNo;
    private String taskType;
    private Long sourceBatchId;
}
