package org.dromara.gateway;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GatewayExceptionHandler {
    @ExceptionHandler(GatewayException.class)
    public ResponseEntity<ErrorResponse> handle(GatewayException exception) {
        return ResponseEntity.status(exception.status())
            .body(new ErrorResponse(exception.code(), exception.getMessage(), exception.rawAiResponse(), exception.usage(),
                exception.rawProviderResponse()));
    }

    public record ErrorResponse(String code, String message, String rawAiResponse, GeminiClient.Usage usage,
                                String rawProviderResponse) {
    }
}
