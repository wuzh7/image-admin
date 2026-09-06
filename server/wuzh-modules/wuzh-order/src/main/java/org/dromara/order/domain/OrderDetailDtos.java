package org.dromara.order.domain;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class OrderDetailDtos {

    private OrderDetailDtos() {
    }

    public record ParentOrderRequest(
        String parentOrderSn,
        String sendWarehouseName,
        String fulfillmentMallId,
        Object estimatedAmount,
        String shippingCompanyName,
        List<GoodsRequest> relatedGoodsInfoForAggregation
    ) {
    }

    public record GoodsRequest(
        String goodsName,
        String orderSn,
        String parentOrderSn,
        Integer skuQuantity,
        String spec,
        String thumbUrl
    ) {
    }

    public record ImportResult(int received, int created, int updated) {
    }

    @Data
    public static class Query {
        private String parentOrderSn;
        private String orderSn;
        private String fulfillmentMallId;
        private String sendWarehouseName;
        private String shippingCompanyName;
    }

    @Data
    public static class SaveRequest {
        @NotNull(message = "主键不能为空", groups = EditGroup.class)
        private Long id;

        @NotBlank(message = "主订单号不能为空", groups = {AddGroup.class, EditGroup.class})
        @Size(max = 64, groups = {AddGroup.class, EditGroup.class})
        private String parentOrderSn;

        @Size(max = 64, groups = {AddGroup.class, EditGroup.class})
        private String fulfillmentMallId;

        private BigDecimal estimatedAmount;

        @Size(max = 128, groups = {AddGroup.class, EditGroup.class})
        private String sendWarehouseName;

        @Size(max = 128, groups = {AddGroup.class, EditGroup.class})
        private String shippingCompanyName;

        @NotBlank(message = "子订单号不能为空", groups = {AddGroup.class, EditGroup.class})
        @Size(max = 64, groups = {AddGroup.class, EditGroup.class})
        private String orderSn;

        @Size(max = 1000, groups = {AddGroup.class, EditGroup.class})
        private String goodsName;

        @NotNull(message = "数量不能为空", groups = {AddGroup.class, EditGroup.class})
        @Min(value = 1, message = "数量必须大于0", groups = {AddGroup.class, EditGroup.class})
        private Integer skuQuantity;

        @Size(max = 512, groups = {AddGroup.class, EditGroup.class})
        private String spec;

        @Size(max = 1000, groups = {AddGroup.class, EditGroup.class})
        private String thumbUrl;
    }

    public record DetailView(
        Long id,
        String parentOrderSn,
        String fulfillmentMallId,
        BigDecimal estimatedAmount,
        String sendWarehouseName,
        String shippingCompanyName,
        String orderSn,
        String goodsName,
        Integer skuQuantity,
        String spec,
        String thumbUrl,
        LocalDateTime createTime,
        LocalDateTime updateTime
    ) {
    }

    @Data
    @ExcelIgnoreUnannotated
    public static class ExportRow {
        @ExcelProperty("订单号")
        private String orderNo;
        @ExcelProperty("店铺ID")
        private String fulfillmentMallId;
        @ExcelProperty("运费")
        private BigDecimal estimatedAmount;
        @ExcelProperty("下单工厂")
        private String sendWarehouseName;
        @ExcelProperty("物流商")
        private String shippingCompanyName;
        @ExcelProperty("子订单号")
        private String orderSn;
        @ExcelProperty("商品名称")
        private String goodsName;
        @ExcelProperty("主订单号")
        private String parentOrderSn;
        @ExcelProperty("数量")
        private Integer skuQuantity;
        @ExcelProperty("SKU")
        private String spec;
        @ExcelProperty("图片地址")
        private String thumbUrl;
    }
}
