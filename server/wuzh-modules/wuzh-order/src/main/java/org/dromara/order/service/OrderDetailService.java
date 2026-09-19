package org.dromara.order.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.dromara.common.core.domain.PageResult;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.order.domain.OrderDetail;
import org.dromara.order.domain.OrderDetailDtos;
import org.dromara.order.mapper.OrderDetailMapper;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class OrderDetailService {

    private static final int MAX_DETAILS_PER_REQUEST = 1000;

    private final OrderDetailMapper orderDetailMapper;
    private final OrderStoreCatalog storeCatalog;

    @Autowired
    public OrderDetailService(OrderDetailMapper orderDetailMapper, OrderStoreCatalog storeCatalog) {
        this.orderDetailMapper = orderDetailMapper;
        this.storeCatalog = storeCatalog;
    }

    OrderDetailService(OrderDetailMapper orderDetailMapper) {
        this(orderDetailMapper, new OrderStoreCatalog(""));
    }

    @Transactional(rollbackFor = Exception.class)
    public OrderDetailDtos.ImportResult importBatch(List<OrderDetailDtos.ParentOrderRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new ServiceException("订单数组不能为空");
        }
        Map<String, OrderDetail> incoming = new LinkedHashMap<>();
        int received = 0;
        for (int parentIndex = 0; parentIndex < requests.size(); parentIndex++) {
            OrderDetailDtos.ParentOrderRequest request = requests.get(parentIndex);
            validateParent(request, parentIndex);
            for (int goodsIndex = 0; goodsIndex < request.relatedGoodsInfoForAggregation().size(); goodsIndex++) {
                OrderDetailDtos.GoodsRequest goods = request.relatedGoodsInfoForAggregation().get(goodsIndex);
                received++;
                if (received > MAX_DETAILS_PER_REQUEST) {
                    throw new ServiceException("单次请求最多接收1000条子订单明细");
                }
                validateGoods(request, goods, parentIndex, goodsIndex);
                incoming.put(goods.orderSn(), toEntity(request, goods));
            }
        }

        Map<String, OrderDetail> existing = orderDetailMapper.selectList(
                new QueryWrapper<OrderDetail>().in("order_sn", incoming.keySet()))
            .stream()
            .collect(Collectors.toMap(OrderDetail::getOrderSn, Function.identity()));

        int created = 0;
        int updated = 0;
        for (OrderDetail detail : incoming.values()) {
            OrderDetail stored = existing.get(detail.getOrderSn());
            if (stored == null) {
                if (orderDetailMapper.insert(detail) != 1) {
                    throw new ServiceException("订单明细新增失败");
                }
                created++;
            } else {
                detail.setId(stored.getId());
                if (orderDetailMapper.updateById(detail) != 1) {
                    throw new ServiceException("订单明细修改失败");
                }
                updated++;
            }
        }
        return new OrderDetailDtos.ImportResult(received, created, updated);
    }

    public PageResult<OrderDetailDtos.DetailView> queryPage(OrderDetailDtos.Query query, PageQuery pageQuery) {
        Page<OrderDetail> page = orderDetailMapper.selectPage(pageQuery.build(), buildQuery(query));
        Map<String, OrderStoreCatalog.StoreInfo> stores = storeCatalog.read();
        return PageResult.build(page.getRecords().stream().map(detail -> toView(detail, stores)).toList(), page.getTotal());
    }

    public List<OrderDetailDtos.ExportRow> queryExport(OrderDetailDtos.Query query) {
        return orderDetailMapper.selectList(buildQuery(query)).stream().map(this::toExportRow).toList();
    }

    public OrderDetailDtos.DetailView queryById(long id) {
        OrderDetail detail = orderDetailMapper.selectById(id);
        if (detail == null) {
            throw new ServiceException("订单明细不存在");
        }
        return toView(detail, storeCatalog.read());
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(OrderDetailDtos.SaveRequest request) {
        ensureOrderSnAvailable(request.getOrderSn(), null);
        OrderDetail detail = fromSaveRequest(request);
        if (orderDetailMapper.insert(detail) != 1) {
            throw new ServiceException("新增订单明细失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(OrderDetailDtos.SaveRequest request) {
        if (orderDetailMapper.selectById(request.getId()) == null) {
            throw new ServiceException("订单明细不存在");
        }
        ensureOrderSnAvailable(request.getOrderSn(), request.getId());
        OrderDetail detail = fromSaveRequest(request);
        detail.setId(request.getId());
        if (orderDetailMapper.updateById(detail) != 1) {
            throw new ServiceException("修改订单明细失败");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (orderDetailMapper.deleteByIds(ids) != ids.size()) {
            throw new ServiceException("删除订单明细失败");
        }
    }

    private QueryWrapper<OrderDetail> buildQuery(OrderDetailDtos.Query query) {
        OrderDetailDtos.Query safeQuery = query == null ? new OrderDetailDtos.Query() : query;
        return new QueryWrapper<OrderDetail>()
            .like(hasText(safeQuery.getParentOrderSn()), "parent_order_sn", safeQuery.getParentOrderSn())
            .like(hasText(safeQuery.getOrderSn()), "order_sn", safeQuery.getOrderSn())
            .eq(hasText(safeQuery.getFulfillmentMallId()), "fulfillment_mall_id", safeQuery.getFulfillmentMallId())
            .like(hasText(safeQuery.getSendWarehouseName()), "send_warehouse_name", safeQuery.getSendWarehouseName())
            .like(hasText(safeQuery.getShippingCompanyName()), "shipping_company_name",
                safeQuery.getShippingCompanyName())
            .orderByDesc("update_time", "id");
    }

    private void ensureOrderSnAvailable(String orderSn, Long excludedId) {
        Long count = orderDetailMapper.selectCount(new QueryWrapper<OrderDetail>()
            .eq("order_sn", orderSn)
            .ne(excludedId != null, "id", excludedId));
        if (count != null && count > 0) {
            throw new ServiceException("子订单号已存在");
        }
    }

    private OrderDetail fromSaveRequest(OrderDetailDtos.SaveRequest request) {
        validateAdminRequest(request);
        OrderDetail detail = new OrderDetail();
        detail.setParentOrderSn(request.getParentOrderSn().trim());
        detail.setFulfillmentMallId(trimToNull(request.getFulfillmentMallId()));
        detail.setEstimatedAmount(request.getEstimatedAmount() == null ? null
            : request.getEstimatedAmount().setScale(2, RoundingMode.HALF_UP));
        detail.setShippingSettleAmount(request.getShippingSettleAmount() == null ? null
            : request.getShippingSettleAmount().setScale(2, RoundingMode.HALF_UP));
        detail.setSalesSettleAmount(request.getSalesSettleAmount() == null ? null
            : request.getSalesSettleAmount().setScale(2, RoundingMode.HALF_UP));
        detail.setSiteName(trimToNull(request.getSiteName()));
        detail.setCustomizedProducts(request.getCustomizedProducts());
        detail.setSendWarehouseName(trimToNull(request.getSendWarehouseName()));
        detail.setShippingCompanyName(trimToNull(request.getShippingCompanyName()));
        detail.setOrderSn(request.getOrderSn().trim());
        detail.setGoodsName(trimToNull(request.getGoodsName()));
        detail.setSkuQuantity(request.getSkuQuantity());
        detail.setSpec(trimToNull(request.getSpec()));
        detail.setThumbUrl(trimToNull(request.getThumbUrl()));
        return detail;
    }

    private void validateAdminRequest(OrderDetailDtos.SaveRequest request) {
        if (request.getEstimatedAmount() != null && request.getEstimatedAmount().signum() < 0) {
            throw new ServiceException("运费不能小于0");
        }
        if (request.getShippingSettleAmount() != null && request.getShippingSettleAmount().signum() < 0) {
            throw new ServiceException("订单运费预计冲回不能小于0");
        }
        if (request.getSalesSettleAmount() != null && request.getSalesSettleAmount().signum() < 0) {
            throw new ServiceException("子订单预计回款不能小于0");
        }
        validateThumbUrl(request.getThumbUrl(), "thumbUrl");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private OrderDetailDtos.DetailView toView(OrderDetail detail, Map<String, OrderStoreCatalog.StoreInfo> stores) {
        OrderStoreCatalog.StoreInfo store = stores.get(detail.getFulfillmentMallId());
        return new OrderDetailDtos.DetailView(
            detail.getId(), detail.getParentOrderSn(), detail.getFulfillmentMallId(),
            store == null ? null : store.name(), store == null ? null : store.remarks(),
            detail.getEstimatedAmount(), detail.getShippingSettleAmount(), detail.getSalesSettleAmount(),
            detail.getSiteName(), detail.getCustomizedProducts(), detail.getSendWarehouseName(),
            detail.getShippingCompanyName(),
            detail.getOrderSn(), detail.getGoodsName(), detail.getSkuQuantity(), detail.getSpec(),
            detail.getThumbUrl(), detail.getCreateTime(), detail.getUpdateTime()
        );
    }

    private OrderDetailDtos.ExportRow toExportRow(OrderDetail detail) {
        OrderDetailDtos.ExportRow row = new OrderDetailDtos.ExportRow();
        row.setOrderNo(detail.getParentOrderSn());
        row.setFulfillmentMallId(detail.getFulfillmentMallId());
        row.setEstimatedAmount(detail.getEstimatedAmount());
        row.setShippingSettleAmount(detail.getShippingSettleAmount());
        row.setSalesSettleAmount(detail.getSalesSettleAmount());
        row.setSiteName(detail.getSiteName());
        row.setCustomizedProducts(detail.getCustomizedProducts());
        row.setSendWarehouseName(detail.getSendWarehouseName());
        row.setShippingCompanyName(detail.getShippingCompanyName());
        row.setOrderSn(detail.getOrderSn());
        row.setGoodsName(detail.getGoodsName());
        row.setParentOrderSn(detail.getParentOrderSn());
        row.setSkuQuantity(detail.getSkuQuantity());
        row.setSpec(detail.getSpec());
        row.setThumbUrl(detail.getThumbUrl());
        return row;
    }

    private void validateParent(OrderDetailDtos.ParentOrderRequest request, int parentIndex) {
        String path = "orders[" + parentIndex + "]";
        if (request == null) {
            throw new ServiceException(path + "不能为空");
        }
        requireText(request.parentOrderSn(), 64, path + ".parentOrderSn");
        requireMaxLength(request.sendWarehouseName(), 128, path + ".sendWarehouseName");
        requireMaxLength(request.fulfillmentMallId(), 64, path + ".fulfillmentMallId");
        requireMaxLength(request.shippingCompanyName(), 128, path + ".shippingCompanyName");
        requireMaxLength(request.siteName(), 128, path + ".siteName");
        parseAmount(request.estimatedAmount(), path + ".estimatedAmount");
        parseAmount(request.shippingSettleAmount(), path + ".shippingSettleAmount");
        if (request.relatedGoodsInfoForAggregation() == null
            || request.relatedGoodsInfoForAggregation().isEmpty()) {
            throw new ServiceException(path + ".relatedGoodsInfoForAggregation不能为空");
        }
    }

    private void validateGoods(OrderDetailDtos.ParentOrderRequest parent,
                               OrderDetailDtos.GoodsRequest goods,
                               int parentIndex,
                               int goodsIndex) {
        String path = "orders[" + parentIndex + "].relatedGoodsInfoForAggregation[" + goodsIndex + "]";
        if (goods == null) {
            throw new ServiceException(path + "不能为空");
        }
        requireText(goods.orderSn(), 64, path + ".orderSn");
        requireText(goods.parentOrderSn(), 64, path + ".parentOrderSn");
        if (!parent.parentOrderSn().equals(goods.parentOrderSn())) {
            throw new ServiceException(path + ".parentOrderSn必须与外层主订单号一致");
        }
        if (goods.skuQuantity() == null || goods.skuQuantity() <= 0) {
            throw new ServiceException(path + ".skuQuantity必须大于0");
        }
        requireMaxLength(goods.goodsName(), 1000, path + ".goodsName");
        requireMaxLength(goods.spec(), 512, path + ".spec");
        requireMaxLength(goods.thumbUrl(), 1000, path + ".thumbUrl");
        parseAmount(goods.salesSettleAmount(), path + ".salesSettleAmount");
        validateThumbUrl(goods.thumbUrl(), path + ".thumbUrl");
    }

    private void requireText(String value, int maxLength, String path) {
        if (value == null || value.isBlank()) {
            throw new ServiceException(path + "不能为空");
        }
        requireMaxLength(value, maxLength, path);
    }

    private void requireMaxLength(String value, int maxLength, String path) {
        if (value != null && value.length() > maxLength) {
            throw new ServiceException(path + "长度不能超过" + maxLength);
        }
    }

    private void validateThumbUrl(String value, String path) {
        if (value == null || value.isBlank()) {
            return;
        }
        try {
            URI uri = new URI(value);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
                throw new ServiceException(path + "必须是HTTP或HTTPS地址");
            }
        } catch (URISyntaxException e) {
            throw new ServiceException(path + "必须是有效地址");
        }
    }

    private OrderDetail toEntity(OrderDetailDtos.ParentOrderRequest request, OrderDetailDtos.GoodsRequest goods) {
        OrderDetail detail = new OrderDetail();
        detail.setParentOrderSn(request.parentOrderSn());
        detail.setFulfillmentMallId(request.fulfillmentMallId());
        detail.setEstimatedAmount(parseAmount(request.estimatedAmount(), "estimatedAmount"));
        detail.setShippingSettleAmount(parseAmount(request.shippingSettleAmount(), "shippingSettleAmount"));
        detail.setSalesSettleAmount(parseAmount(goods.salesSettleAmount(), "salesSettleAmount"));
        detail.setSiteName(request.siteName());
        detail.setCustomizedProducts(goods.customizedProducts());
        detail.setSendWarehouseName(request.sendWarehouseName());
        detail.setShippingCompanyName(request.shippingCompanyName());
        detail.setOrderSn(goods.orderSn());
        detail.setGoodsName(goods.goodsName());
        detail.setSkuQuantity(goods.skuQuantity());
        detail.setSpec(goods.spec());
        detail.setThumbUrl(goods.thumbUrl());
        return detail;
    }

    private BigDecimal parseAmount(Object value, String path) {
        if (value == null) {
            return null;
        }
        try {
            String text = value.toString().trim();
            if (text.startsWith("$")) {
                text = text.substring(1).trim();
            }
            BigDecimal amount = new BigDecimal(text);
            if (amount.signum() < 0) {
                throw new ServiceException(path + "不能小于0");
            }
            return amount.setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            throw new ServiceException(path + "格式错误");
        }
    }
}
