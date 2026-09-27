package org.dromara.order.tro;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.util.RandomUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.order.config.TroProperties;
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
@RequestMapping("/open-api/tro/cases")
public class TroExternalController {
    private static final String API_KEY_HEADER = "X-API-Key";

    private final TroCaseService service;
    private final TroProperties properties;

    @PostMapping("/query")
    public ResponseEntity<R<List<TroExternalDtos.Response>>> query(
        @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
        @RequestBody TroExternalDtos.Query query,
        HttpServletRequest request
    ) {
        if (!validApiKey(apiKey)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(R.fail(HttpStatus.UNAUTHORIZED.value(), "API Key无效"));
        }
        try {
            return ResponseEntity.ok(R.ok(service.externalQuery(query)));
        } catch (ServiceException e) {
            return ResponseEntity.badRequest().body(R.fail(HttpStatus.BAD_REQUEST.value(), e.getMessage()));
        } catch (RuntimeException e) {
            String errorId = RandomUtil.randomNumbers(8);
            log.error("TRO外部接口处理失败, sourceIp={}, 错误编号: {}", request.getRemoteAddr(), errorId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(R.fail(HttpStatus.INTERNAL_SERVER_ERROR.value(), "发生系统异常，请联系管理员 [错误编号: " + errorId + "]"));
        }
    }

    private boolean validApiKey(String candidate) {
        if (candidate == null) return false;
        return MessageDigest.isEqual(properties.getApiKey().getBytes(StandardCharsets.UTF_8),
            candidate.getBytes(StandardCharsets.UTF_8));
    }
}
