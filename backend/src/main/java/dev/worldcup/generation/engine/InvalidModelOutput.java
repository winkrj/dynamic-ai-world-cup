package dev.worldcup.generation.engine;

/** Deliberately contains neither provider error text nor a user's prompt. */
public final class InvalidModelOutput extends RuntimeException {
    public InvalidModelOutput() { super("INVALID_MODEL_OUTPUT"); }
}
