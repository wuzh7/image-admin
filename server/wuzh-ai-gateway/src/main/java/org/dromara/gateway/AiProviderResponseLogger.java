package org.dromara.gateway;

import org.slf4j.LoggerFactory;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

final class AiProviderResponseLogger {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ThreadLocal<Capture> CURRENT = new ThreadLocal<>();

    private AiProviderResponseLogger() { }

    static void log(String operation, int status, String body) {
        Capture capture = CURRENT.get();
        if (capture != null) capture.body = body;
        var attributes = RequestContextHolder.getRequestAttributes();
        String requestId = attributes instanceof ServletRequestAttributes servlet
            ? servlet.getRequest().getHeader("X-Request-Id") : null;
        // Escape the complete body into one log line so concurrent responses cannot interleave.
        LoggerFactory.getLogger(AiProviderResponseLogger.class).info(
            "AI_PROVIDER_RESPONSE requestId={} operation={} status={} response={}",
            requestId, operation, status, JSON.writeValueAsString(Map.of(
                "hasGroundingMetadata", hasGroundingMetadata(body), "rawResponseJson", body == null ? "" : body)));
    }

    static Capture capture() {
        Capture capture = new Capture();
        CURRENT.set(capture);
        return capture;
    }

    static final class Capture implements AutoCloseable {
        private String body;

        String body() { return body; }

        @Override
        public void close() { CURRENT.remove(); }
    }

    private static boolean hasGroundingMetadata(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            return JSON.readTree(body).findValue("groundingMetadata") != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
