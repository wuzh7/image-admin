package org.dromara.image.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import cn.hutool.core.util.RandomUtil;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.image.config.ImageProperties;
import org.dromara.image.domain.ExternalInfringementDtos;
import org.dromara.image.service.ExternalInfringementService;
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
@RequestMapping("/open-api/infringement")
public class ExternalInfringementController {
    private final ExternalInfringementService service;
    private final ImageProperties properties;

    @PostMapping("/images/claim")
    public ResponseEntity<R<List<ExternalInfringementDtos.Claim>>> claim(
        @RequestHeader(value = "X-API-Key", required = false) String apiKey, HttpServletRequest request) {
        if (!validApiKey(apiKey)) return ResponseEntity.status(401).body(R.fail(401, "API Key无效"));
        try {
            return ResponseEntity.ok(R.ok(service.claim()));
        } catch (ServiceException e) {
            int status = e.getCode() == null ? 400 : e.getCode();
            return ResponseEntity.status(status).body(R.fail(status, e.getMessage()));
        } catch (RuntimeException e) {
            String errorId = RandomUtil.randomNumbers(8);
            log.error("三方图片领取失败, sourceIp={}, 错误编号: {}", request.getRemoteAddr(), errorId, e);
            return ResponseEntity.status(500).body(R.fail(500, "发生系统异常，请联系管理员 [错误编号: " + errorId + "]"));
        }
    }

    @PostMapping("/images/result")
    public ResponseEntity<R<Void>> result(@RequestHeader(value = "X-API-Key", required = false) String apiKey,
                                          @RequestBody ExternalInfringementDtos.Result body, HttpServletRequest request) {
        if (!validApiKey(apiKey)) return ResponseEntity.status(401).body(R.fail(401, "API Key无效"));
        try {
            service.complete(body);
            return ResponseEntity.ok(R.ok());
        } catch (ServiceException e) {
            int status = e.getCode() == null ? 400 : e.getCode();
            return ResponseEntity.status(status).body(R.fail(status, e.getMessage()));
        } catch (RuntimeException e) {
            String errorId = RandomUtil.randomNumbers(8);
            log.error("三方图片结果回写失败, sourceIp={}, 错误编号: {}", request.getRemoteAddr(), errorId, e);
            return ResponseEntity.status(500).body(R.fail(500, "发生系统异常，请联系管理员 [错误编号: " + errorId + "]"));
        }
    }

    private boolean validApiKey(String candidate) {
        String expected = properties.getInfringement().getExternalApiKey();
        return candidate != null && expected != null && !expected.isBlank()
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }
}
