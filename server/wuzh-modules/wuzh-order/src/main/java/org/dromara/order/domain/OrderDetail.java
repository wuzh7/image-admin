package org.dromara.order.domain;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ord_order_detail")
public class OrderDetail extends BaseEntity {

    @TableId
    private Long id;
    private Long ownerId;
    private String parentOrderSn;
    private LocalDateTime parentOrderTime;
    private String fulfillmentMallId;
    private BigDecimal estimatedAmount;
    private BigDecimal shippingSettleAmount;
    private BigDecimal salesSettleAmount;
    private String siteName;
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private String customizedProducts;
    private String sendWarehouseName;
    private String shippingCompanyName;
    private String orderSn;
    private String goodsName;
    private Integer skuQuantity;
    private String spec;
    private String thumbUrl;
}
