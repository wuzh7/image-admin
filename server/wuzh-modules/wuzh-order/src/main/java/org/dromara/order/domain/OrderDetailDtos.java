package org.dromara.order.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.dromara.common.core.validate.AddGroup;
import org.dromara.common.core.validate.EditGroup;
import org.springframework.format.annotation.DateTimeFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public final class OrderDetailDtos {

    private OrderDetailDtos() {
    }

    public record ParentOrderRequest(
        String parentOrderSn,
        String parentOrderTimeStr,
        String sendWarehouseName,
        String fulfillmentMallId,
        Object estimatedAmount,
        String shippingCompanyName,
        String siteName,
        List<GoodsRequest> relatedGoodsInfoForAggregation,
        Object shippingSettleAmount
    ) {
        public ParentOrderRequest(String parentOrderSn, String sendWarehouseName, String fulfillmentMallId,
                                  Object estimatedAmount, String shippingCompanyName,
                                  List<GoodsRequest> relatedGoodsInfoForAggregation) {
            this(parentOrderSn, null, sendWarehouseName, fulfillmentMallId, estimatedAmount, shippingCompanyName,
                null, relatedGoodsInfoForAggregation, null);
        }
    }

    public record GoodsRequest(
        String goodsName,
        String orderSn,
        String parentOrderSn,
        Integer skuQuantity,
        String spec,
        String thumbUrl,
        Object salesSettleAmount,
        @JsonProperty("customized_products") String customizedProducts
    ) {
        public GoodsRequest(String goodsName, String orderSn, String parentOrderSn, Integer skuQuantity,
                            String spec, String thumbUrl) {
            this(goodsName, orderSn, parentOrderSn, skuQuantity, spec, thumbUrl, null, null);
        }
    }

    public record ImportResult(int received, int created, int updated) {
    }

    @Data
    public static class Query {
        private String parentOrderSn;
        private String orderSn;
        private String fulfillmentMallId;
        private String storeRemark;
        private String customizedProducts;
        private String sendWarehouseName;
        private String shippingCompanyName;
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime beginParentOrderTime;
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime endParentOrderTime;
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

        private BigDecimal shippingSettleAmount;

        private BigDecimal salesSettleAmount;

        @Size(max = 128, groups = {AddGroup.class, EditGroup.class})
        private String siteName;

        private String customizedProducts;

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
        Long ownerId,
        String parentOrderSn,
        String parentOrderTimeStr,
        String fulfillmentMallId,
        String storeName,
        String storeRemark,
        BigDecimal estimatedAmount,
        BigDecimal shippingSettleAmount,
        BigDecimal salesSettleAmount,
        String siteName,
        String customizedProducts,
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
        @ExcelProperty("主订单号")
        private String parentOrderSn;
        @ExcelProperty("订单创建时间")
        private String parentOrderTimeStr;
        @ExcelProperty("子订单号")
        private String orderSn;
        @ExcelProperty("店铺ID")
        private String fulfillmentMallId;
        @ExcelProperty("店铺名称")
        private String storeName;
        @ExcelProperty("店铺备注")
        private String storeRemark;
        @ExcelProperty("下单工厂")
        private String sendWarehouseName;
        @ExcelProperty("物流商")
        private String shippingCompanyName;
        @ExcelProperty("图片")
        private String thumbUrl;
        @ExcelProperty("商品名称")
        private String goodsName;
        @ExcelProperty("SKU")
        private String spec;
        @ExcelProperty("数量")
        private Integer skuQuantity;
        @ExcelProperty("订单运费预计冲回")
        private BigDecimal shippingSettleAmount;
        @ExcelProperty("子订单预计回款")
        private BigDecimal salesSettleAmount;
        @ExcelProperty("销售站点")
        private String siteName;
        @ExcelProperty("是否定制")
        private String customizedProducts;
        @ExcelProperty("运费")
        private BigDecimal estimatedAmount;
        @ExcelProperty("更新时间")
        private String updateTime;
    }
}
