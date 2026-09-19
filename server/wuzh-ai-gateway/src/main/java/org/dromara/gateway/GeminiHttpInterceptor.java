package org.dromara.gateway;

import okhttp3.Interceptor;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

final class GeminiHttpInterceptor implements Interceptor {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        var body = request.body();
        if (body != null && body.contentType() != null && body.contentType().subtype().contains("json")) {
            Buffer buffer = new Buffer();
            body.writeTo(buffer);
            Object payload = JSON.readValue(buffer.readUtf8(), Object.class);
            normalizeTools(payload);
            request = request.newBuilder().method(request.method(),
                RequestBody.create(JSON.writeValueAsString(payload), body.contentType())).build();
        }
        Response response = chain.proceed(request);
        ResponseBody responseBody = response.body();
        if (responseBody != null && (request.url().encodedPath().contains("/models/")
            || request.url().encodedPath().contains("/batches/")
            || responseBody.contentType() != null && responseBody.contentType().subtype().contains("json"))) {
            String raw = responseBody.string();
            AiProviderResponseLogger.log("OFFICIAL " + request.url().encodedPath(), response.code(), raw);
            return response.newBuilder().body(ResponseBody.create(raw, responseBody.contentType())).build();
        }
        return response;
    }

    @SuppressWarnings("unchecked")
    private static void normalizeTools(Object value) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("tools") instanceof List<?> tools) {
                for (Object tool : tools) {
                    if (tool instanceof Map<?, ?> fields && fields.containsKey("googleSearch")) {
                        var mutable = (Map<String, Object>) fields;
                        mutable.put("google_search", mutable.remove("googleSearch"));
                    }
                }
            }
            map.values().forEach(GeminiHttpInterceptor::normalizeTools);
        } else if (value instanceof List<?> list) {
            list.forEach(GeminiHttpInterceptor::normalizeTools);
        }
    }
}
