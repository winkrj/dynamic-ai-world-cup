package dev.worldcup.shared;

import java.util.OptionalLong;

/** Stable business failures, independent of HTTP and provider exception text. */
public final class Failure extends RuntimeException {
    public enum Code {
        INVALID_INPUT, NOT_FOUND, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT,
        OPERATION_IN_PROGRESS, REGENERATION_EXHAUSTED, ALREADY_FROZEN,
        QUALITY_GATE_FAILED, CLARIFICATION_REQUIRED, UNSUPPORTED_REQUEST,
        RATE_LIMITED, PROVIDER_UNAVAILABLE, INVALID_SELECTION, SESSION_NOT_COMPLETED, INTERNAL_ERROR
    }
    private final Code code;
    private final OptionalLong retryAfterSeconds;
    public Failure(Code code) { this(code, OptionalLong.empty()); }
    private Failure(Code code, OptionalLong retryAfterSeconds) {
        super(code.name()); this.code = code; this.retryAfterSeconds = retryAfterSeconds;
    }
    public Code code() { return code; }
    public OptionalLong retryAfterSeconds() { return retryAfterSeconds; }
    public static Failure of(Code code) { return new Failure(code); }
    public static Failure rateLimited(long seconds) {
        if (seconds < 1) throw new IllegalArgumentException("Retry delay must be positive");
        return new Failure(Code.RATE_LIMITED, OptionalLong.of(seconds));
    }
}
