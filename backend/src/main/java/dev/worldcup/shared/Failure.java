package dev.worldcup.shared;

/** Stable business failures, independent of HTTP and provider exception text. */
public final class Failure extends RuntimeException {
    public enum Code {
        INVALID_INPUT, NOT_FOUND, VERSION_CONFLICT, IDEMPOTENCY_CONFLICT,
        OPERATION_IN_PROGRESS, REGENERATION_EXHAUSTED, ALREADY_FROZEN,
        QUALITY_GATE_FAILED, CLARIFICATION_REQUIRED, UNSUPPORTED_REQUEST,
        RATE_LIMITED, PROVIDER_UNAVAILABLE, INVALID_SELECTION, SESSION_NOT_COMPLETED, INTERNAL_ERROR
    }
    private final Code code;
    public Failure(Code code) { super(code.name()); this.code = code; }
    public Code code() { return code; }
    public static Failure of(Code code) { return new Failure(code); }
}
