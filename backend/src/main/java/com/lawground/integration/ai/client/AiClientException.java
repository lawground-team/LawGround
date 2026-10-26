package com.lawground.integration.ai.client;

/**
 * Safe failure metadata only. Remote messages, response bodies and transport causes are discarded.
 */
public final class AiClientException extends RuntimeException {
    public enum Kind {
        CONFIGURATION,
        INPUT_INVALID,
        TRANSPORT,
        TIMEOUT,
        PROTOCOL,
        REMOTE_ERROR,
        NOT_READY,
        VERSION_MISMATCH
    }

    private final Kind kind;
    private final int httpStatus;
    private final String code;
    private final boolean retryable;
    private final java.time.Instant retryAfter;

    public AiClientException(Kind kind, int httpStatus, String code, boolean retryable) {
        this(kind, httpStatus, code, retryable, null);
    }

    public AiClientException(
            Kind kind,
            int httpStatus,
            String code,
            boolean retryable,
            java.time.Instant retryAfter) {
        super("Internal AI call failed: " + kind + " (" + code + ")");
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryable = retryable;
        this.retryAfter = retryAfter;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    public java.time.Instant retryAfter() {
        return retryAfter;
    }
}
