package org.dromara.order.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.util.RandomUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.order.config.OrderProperties;
import org.dromara.order.domain.OrderDetailDtos;
import org.dromara.order.service.OrderDetailService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@Slf4j
@SaIgnore
@RestController
@RequiredArgsConstructor
@RequestMapping("/open-api/order-details")
public class OrderDetailOpenApiController {

    private static final String API_KEY_HEADER = "X-API-Key";

    private final OrderDetailService orderDetailService;
    private final OrderProperties properties;

    @PostMapping("/batch")
    public ResponseEntity<R<OrderDetailDtos.ImportResult>> importBatch(
        @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
        @RequestBody List<OrderDetailDtos.ParentOrderRequest> requests,
        HttpServletRequest servletRequest
    ) {
        if (!validApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail(HttpStatus.UNAUTHORIZED.value(), "API Key无效"));
        }
        try {
            OrderDetailDtos.ImportResult result = orderDetailService.importBatch(requests);
            log.info("外部订单接口处理完成, sourceIp={}, received={}, created={}, updated={}",
                servletRequest.getRemoteAddr(), result.received(), result.created(), result.updated());
            return ResponseEntity.ok(R.ok(result));
        } catch (ServiceException e) {
            log.warn("外部订单接口参数校验失败, sourceIp={}, reason={}",
                servletRequest.getRemoteAddr(), e.getMessage());
            return ResponseEntity.badRequest()
                .body(R.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
        } catch (RuntimeException e) {
            String errorId = RandomUtil.randomNumbers(8);
            log.error("外部订单接口处理失败, sourceIp={}, 错误编号: {}",
                servletRequest.getRemoteAddr(), errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "发生系统异常，请联系管理员 [错误编号: " + errorId + "]"));
        }
    }

    private boolean validApiKey(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
            properties.getApiKey().getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8)
        );
    }
}
