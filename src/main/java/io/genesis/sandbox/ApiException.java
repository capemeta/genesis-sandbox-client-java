package io.genesis.sandbox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured error returned by the Genesis Sandbox Service. */
public final class ApiException extends IOException {
    private final int statusCode;
    private final String errorCode;
    private final String requestId;
    private final Map<String, Object> details;
    private final boolean retryable;
    private final Duration retryAfter;
    private final String responseBody;

    ApiException(int statusCode, byte[] body, Duration retryAfter) {
        this(statusCode, body, retryAfter, JsonCodec.readLenient(body, ErrorEnvelope.class));
    }

    private ApiException(int statusCode, byte[] body, Duration retryAfter, ErrorEnvelope envelope) {
        super(message(statusCode, body, envelope));
        this.statusCode = statusCode;
        this.errorCode = envelope == null ? "" : value(envelope.errorCode);
        this.requestId = envelope == null ? "" : value(envelope.requestId);
        this.details = envelope == null || envelope.details == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(envelope.details));
        Object retryableValue = details.get("retryable");
        this.retryable = retryableValue instanceof Boolean
                ? (Boolean) retryableValue
                : statusCode == 429 || statusCode >= 500;
        this.retryAfter = retryAfter == null ? Duration.ZERO : retryAfter;
        this.responseBody = body == null ? "" : new String(body, StandardCharsets.UTF_8);
    }

    public int statusCode() { return statusCode; }
    public String errorCode() { return errorCode; }
    public String requestId() { return requestId; }
    public Map<String, Object> details() { return details; }
    public boolean retryable() { return retryable; }
    public Duration retryAfter() { return retryAfter; }
    public String responseBody() { return responseBody; }

    private static String message(int status, byte[] body, ErrorEnvelope envelope) {
        String raw = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        String code = envelope == null ? "" : value(envelope.errorCode);
        String requestId = envelope == null ? "" : value(envelope.requestId);
        String detail = envelope == null ? "" : value(envelope.message);
        return "sandbox API " + (code.isEmpty() ? status : code)
                + " (status=" + status + ", request_id="
                + (requestId.isEmpty() ? "unknown" : requestId) + "): "
                + (detail.isEmpty() ? raw : detail);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    public static final class ErrorEnvelope {
        public String errorCode;
        public String message;
        public String requestId;
        public Map<String, Object> details = new LinkedHashMap<>();
    }
}
