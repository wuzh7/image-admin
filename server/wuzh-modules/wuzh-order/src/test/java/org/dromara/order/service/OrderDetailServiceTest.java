package org.dromara.order.service;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.order.domain.OrderDetail;
import org.dromara.order.domain.OrderDetailDtos;
import org.dromara.order.mapper.OrderDetailMapper;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.format.support.DefaultFormattingConversionService;
import org.springframework.web.bind.WebDataBinder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("local")
class OrderDetailServiceTest {

    @Mock
    private OrderDetailMapper orderDetailMapper;

    @Test
    void filtersByCustomizedProductsAndStoreRemark() {
        OrderStoreCatalog storeCatalog = mock(OrderStoreCatalog.class);
        when(storeCatalog.read()).thenReturn(Map.of(
            "MALL-1", new OrderStoreCatalog.StoreInfo("一号店", "重点客户"),
            "MALL-2", new OrderStoreCatalog.StoreInfo("二号店", "普通客户")
        ));
        when(orderDetailMapper.selectList(any())).thenReturn(List.of());
        OrderDetailDtos.Query query = new OrderDetailDtos.Query();
        query.setCustomizedProducts("是");
        query.setStoreRemark("重点");

        new OrderDetailService(orderDetailMapper, storeCatalog).queryExport(query);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OrderDetail>> wrapper =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(orderDetailMapper).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getCustomSqlSegment())
            .contains("customized_products", "fulfillment_mall_id");
        assertThat(wrapper.getValue().getParamNameValuePairs().values())
            .contains("%是%", "MALL-1")
            .doesNotContain("MALL-2");
    }

    @Test
    void filtersExportByParentOrderCreationTime() {
        OrderStoreCatalog storeCatalog = mock(OrderStoreCatalog.class);
        when(storeCatalog.read()).thenReturn(Map.of());
        when(orderDetailMapper.selectList(any())).thenReturn(List.of());
        OrderDetailDtos.Query query = new OrderDetailDtos.Query();
        query.setBeginParentOrderTime(LocalDateTime.parse("2026-09-23T00:00:00"));
        query.setEndParentOrderTime(LocalDateTime.parse("2026-09-23T23:59:59"));

        new OrderDetailService(orderDetailMapper, storeCatalog).queryExport(query);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<OrderDetail>> wrapper =
            ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper.class);
        verify(orderDetailMapper).selectList(wrapper.capture());
        assertThat(wrapper.getValue().getCustomSqlSegment()).contains("parent_order_time", ">=", "<=");
        assertThat(wrapper.getValue().getParamNameValuePairs().values())
            .contains(query.getBeginParentOrderTime(), query.getEndParentOrderTime());
    }

    @Test
    void bindsParentOrderTimeRangeFromRequestParameters() {
        OrderDetailDtos.Query query = new OrderDetailDtos.Query();
        WebDataBinder binder = new WebDataBinder(query);
        binder.setConversionService(new DefaultFormattingConversionService());

        binder.bind(new MutablePropertyValues(Map.of(
            "beginParentOrderTime", "2026-09-23 00:00:00",
            "endParentOrderTime", "2026-09-23 23:59:59"
        )));

        assertThat(binder.getBindingResult().hasErrors()).isFalse();
        assertThat(query.getBeginParentOrderTime()).isEqualTo(LocalDateTime.parse("2026-09-23T00:00:00"));
        assertThat(query.getEndParentOrderTime()).isEqualTo(LocalDateTime.parse("2026-09-23T23:59:59"));
    }

    @Test
    void returnsParentOrderCreationTimeForDetailView() {
        OrderStoreCatalog storeCatalog = mock(OrderStoreCatalog.class);
        when(storeCatalog.read()).thenReturn(Map.of());
        OrderDetail detail = new OrderDetail();
        detail.setId(1L);
        detail.setParentOrderTime(LocalDateTime.parse("2026-09-23T14:30:00"));
        when(orderDetailMapper.selectOne(any())).thenReturn(detail);

        OrderDetailDtos.DetailView view = new OrderDetailService(orderDetailMapper, storeCatalog).queryById(1L);

        assertThat(view.parentOrderTimeStr()).isEqualTo("2026-09-23 14:30:00");
    }

    @Test
    void customizedProductsAllowsNullToClearExistingValue() throws Exception {
        TableField tableField = OrderDetail.class.getDeclaredField("customizedProducts")
            .getAnnotation(TableField.class);

        assertThat(tableField).isNotNull();
        assertThat(tableField.updateStrategy()).isEqualTo(FieldStrategy.ALWAYS);
    }

    @Test
    void importsSettlementAmountsSiteNameAndCustomizedProducts() throws Exception {
        when(orderDetailMapper.selectList(any())).thenReturn(List.of());
        when(orderDetailMapper.insert(any(OrderDetail.class))).thenReturn(1);
        List<OrderDetailDtos.ParentOrderRequest> requests = new ObjectMapper().readValue("""
            [{
              "parentOrderSn":"PARENT-1",
              "parentOrderTimeStr":"2026-09-23 14:30:00",
              "shippingSettleAmount":"$2.22",
              "siteName":"US",
              "relatedGoodsInfoForAggregation":[{
                "orderSn":"CHILD-1",
                "parentOrderSn":"PARENT-1",
                "skuQuantity":1,
                "salesSettleAmount":"10.51",
                "customized_products":"子订单一"
              },{
                "orderSn":"CHILD-2",
                "parentOrderSn":"PARENT-1",
                "skuQuantity":1,
                "salesSettleAmount":"11.52",
                "customized_products":"子订单二"
              }]
            }]
            """, new TypeReference<>() {
        });

        new OrderDetailService(orderDetailMapper).importBatch(requests);

        ArgumentCaptor<OrderDetail> inserted = ArgumentCaptor.forClass(OrderDetail.class);
        verify(orderDetailMapper, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).allSatisfy(detail ->
            assertThat(detail).extracting("shippingSettleAmount", "siteName")
                .containsExactly(new java.math.BigDecimal("2.22"), "US")
        );
        assertThat(inserted.getAllValues()).extracting(OrderDetail::getCustomizedProducts)
            .containsExactly("子订单一", "子订单二");
        assertThat(inserted.getAllValues()).extracting(OrderDetail::getSalesSettleAmount)
            .containsExactly(new java.math.BigDecimal("10.51"), new java.math.BigDecimal("11.52"));
        assertThat(inserted.getAllValues()).extracting(OrderDetail::getParentOrderTime)
            .containsExactly(LocalDateTime.parse("2026-09-23T14:30:00"), LocalDateTime.parse("2026-09-23T14:30:00"));
    }

    @Test
    void importsNewAndExistingOrderDetailsIdempotently() {
        OrderDetail existing = new OrderDetail();
        existing.setId(10L);
        existing.setOwnerId(123L);
        existing.setOrderSn("CHILD-2");
        when(orderDetailMapper.selectList(any())).thenReturn(List.of(existing));
        when(orderDetailMapper.insert(any(OrderDetail.class))).thenReturn(1);
        when(orderDetailMapper.updateById(any(OrderDetail.class))).thenReturn(1);

        OrderDetailDtos.ParentOrderRequest request = new OrderDetailDtos.ParentOrderRequest(
            "PARENT-1", "Factory", "Mall-1", "$3.57", "Carrier",
            List.of(
                new OrderDetailDtos.GoodsRequest("New goods", "CHILD-1", "PARENT-1", 1, "Black / L", null),
                new OrderDetailDtos.GoodsRequest("Updated goods", "CHILD-2", "PARENT-1", 2, "Black / XL", null)
            )
        );

        OrderDetailDtos.ImportResult result = new OrderDetailService(orderDetailMapper)
            .importBatch(List.of(request));

        assertThat(result.received()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);

        ArgumentCaptor<OrderDetail> inserted = ArgumentCaptor.forClass(OrderDetail.class);
        verify(orderDetailMapper).insert(inserted.capture());
        assertThat(inserted.getValue().getOrderSn()).isEqualTo("CHILD-1");
        assertThat(inserted.getValue().getOwnerId()).isEqualTo(org.dromara.common.core.constant.SystemConstants.SUPER_ADMIN_USER_ID);

        ArgumentCaptor<OrderDetail> updated = ArgumentCaptor.forClass(OrderDetail.class);
        verify(orderDetailMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(10L);
        assertThat(updated.getValue().getGoodsName()).isEqualTo("Updated goods");
        assertThat(updated.getValue().getOwnerId()).isEqualTo(123L);
    }

    @Test
    void editingDetailPreservesAssignedOwner() {
        OrderDetail existing = new OrderDetail();
        existing.setOwnerId(123L);
        when(orderDetailMapper.selectOne(any())).thenReturn(existing);
        when(orderDetailMapper.update(any(OrderDetail.class), any())).thenReturn(1);
        OrderDetailDtos.SaveRequest request = new OrderDetailDtos.SaveRequest();
        request.setId(10L);
        request.setParentOrderSn("PARENT-1");
        request.setOrderSn("CHILD-1");

        new OrderDetailService(orderDetailMapper).update(request);

        ArgumentCaptor<OrderDetail> updated = ArgumentCaptor.forClass(OrderDetail.class);
        verify(orderDetailMapper).update(updated.capture(), any());
        assertThat(updated.getValue().getOwnerId()).isEqualTo(123L);
    }

    @Test
    void failsWhenNewDetailWasNotPersisted() {
        when(orderDetailMapper.selectList(any())).thenReturn(List.of());
        when(orderDetailMapper.insert(any(OrderDetail.class))).thenReturn(0);
        OrderDetailService service = new OrderDetailService(orderDetailMapper);

        assertThatThrownBy(() -> service.importBatch(List.of(
            parent("PARENT-1", "$1.00", List.of(goods("CHILD-1", "PARENT-1", 1, null)))
        )))
            .isInstanceOf(ServiceException.class)
            .hasMessage("订单明细新增失败");
    }

    @Test
    void identifiesEstimatedAmountPathInValidationError() {
        OrderDetailService service = new OrderDetailService(orderDetailMapper);

        assertThatThrownBy(() -> service.importBatch(List.of(
            parent("PARENT-1", "money", List.of(goods("CHILD-1", "PARENT-1", 1, null)))
        )))
            .isInstanceOf(ServiceException.class)
            .hasMessage("orders[0].estimatedAmount格式错误");
    }

    @Test
    void rejectsInvalidParentOrderTimeBeforeWriting() {
        OrderDetailDtos.ParentOrderRequest request = new OrderDetailDtos.ParentOrderRequest(
            "PARENT-1", "2026-02-30 12:00:00", "Factory", "Mall-1", "$1.00", "Carrier", null,
            List.of(goods("CHILD-1", "PARENT-1", 1, null)), null);

        assertThatThrownBy(() -> new OrderDetailService(orderDetailMapper).importBatch(List.of(request)))
            .isInstanceOf(ServiceException.class)
            .hasMessage("orders[0].parentOrderTimeStr格式应为 yyyy-MM-dd HH:mm:ss");
        verifyNoInteractions(orderDetailMapper);
    }

    @Test
    void acceptsExactlyOneThousandDetails() {
        List<OrderDetailDtos.GoodsRequest> goods = IntStream.rangeClosed(1, 1000)
            .mapToObj(index -> goods("CHILD-" + index, "PARENT-1", 1, null))
            .toList();
        when(orderDetailMapper.selectList(any())).thenReturn(List.of());
        when(orderDetailMapper.insert(any(OrderDetail.class))).thenReturn(1);

        OrderDetailDtos.ImportResult result = new OrderDetailService(orderDetailMapper)
            .importBatch(List.of(parent("PARENT-1", "$1.00", goods)));

        assertThat(result.received()).isEqualTo(1000);
        assertThat(result.created()).isEqualTo(1000);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidBatches")
    void rejectsInvalidBatchBeforeWriting(String scenario,
                                          List<OrderDetailDtos.ParentOrderRequest> requests) {
        OrderDetailService service = new OrderDetailService(orderDetailMapper);

        assertThatThrownBy(() -> service.importBatch(requests))
            .isInstanceOf(ServiceException.class);
        verifyNoInteractions(orderDetailMapper);
    }

    private static Stream<Arguments> invalidBatches() {
        OrderDetailDtos.GoodsRequest validGoods = goods("CHILD-1", "PARENT-1", 1, null);
        List<OrderDetailDtos.GoodsRequest> tooMany = IntStream.rangeClosed(1, 1001)
            .mapToObj(index -> goods("CHILD-" + index, "PARENT-1", 1, null))
            .toList();
        return Stream.of(
            Arguments.of("empty batch", List.of()),
            Arguments.of("missing parent order", List.of(parent(" ", "$1.00", List.of(validGoods)))),
            Arguments.of("missing goods", List.of(parent("PARENT-1", "$1.00", List.of()))),
            Arguments.of("mismatched parent order", List.of(parent("PARENT-1", "$1.00",
                List.of(goods("CHILD-1", "PARENT-2", 1, null))))),
            Arguments.of("invalid quantity", List.of(parent("PARENT-1", "$1.00",
                List.of(goods("CHILD-1", "PARENT-1", 0, null))))),
            Arguments.of("invalid amount", List.of(parent("PARENT-1", "money", List.of(validGoods)))),
            Arguments.of("misplaced currency symbol", List.of(parent("PARENT-1", "3$57", List.of(validGoods)))),
            Arguments.of("invalid image url", List.of(parent("PARENT-1", "$1.00",
                List.of(goods("CHILD-1", "PARENT-1", 1, "file:///tmp/a.jpg"))))),
            Arguments.of("more than 1000 details", List.of(parent("PARENT-1", "$1.00", tooMany)))
        );
    }

    private static OrderDetailDtos.ParentOrderRequest parent(
        String parentOrderSn,
        Object estimatedAmount,
        List<OrderDetailDtos.GoodsRequest> goods
    ) {
        return new OrderDetailDtos.ParentOrderRequest(
            parentOrderSn, "Factory", "Mall-1", estimatedAmount, "Carrier", goods
        );
    }

    private static OrderDetailDtos.GoodsRequest goods(
        String orderSn,
        String parentOrderSn,
        Integer quantity,
        String thumbUrl
    ) {
        return new OrderDetailDtos.GoodsRequest(
            "Goods", orderSn, parentOrderSn, quantity, "Black / L", thumbUrl
        );
    }
}
