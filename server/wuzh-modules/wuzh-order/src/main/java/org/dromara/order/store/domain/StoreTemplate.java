package org.dromara.order.store.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("store_template")
public class StoreTemplate extends BaseEntity {
    @TableId
    private Long id;
    private Long ownerId;
    private Long groupId;
    private Long categoryId;
    private String name;
    private String ossKey;
    private String imageUrl;
    private String templateConfigJson;
    private Integer width;
    private Integer height;
    private String status;
}
