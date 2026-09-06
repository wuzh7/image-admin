package org.dromara.order.service;

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

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@Tag("local")
class OrderDetailServiceTest {

    @Mock
    private OrderDetailMapper orderDetailMapper;

    @Test
    void importsNewAndExistingOrderDetailsIdempotently() {
        OrderDetail existing = new OrderDetail();
        existing.setId(10L);
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

        ArgumentCaptor<OrderDetail> updated = ArgumentCaptor.forClass(OrderDetail.class);
        verify(orderDetailMapper).updateById(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo(10L);
        assertThat(updated.getValue().getGoodsName()).isEqualTo("Updated goods");
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
