package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_calibration")
public class StoreCalibration extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long categoryId;
    private String name;
    private String calibrationConfigJson;
    private String backgroundOssKey;
    private String maskOssKey;
    private String status;
    private Integer version;
}
