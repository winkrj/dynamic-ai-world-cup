package dev.worldcup.generation;

import static dev.worldcup.shared.Failure.Code.*;
import dev.worldcup.shared.Failure;

public record Draft(String id, String actorId, State state, int version, int regenerationUsed,
                    GenerationInput input, DraftContent content) {
    public enum State { READY, REGENERATING, FROZEN }
    public void requireVersion(int expected) {
        if (expected != version) throw Failure.of(VERSION_CONFLICT);
    }
    public void requireReady() {
        if (state == State.REGENERATING) throw Failure.of(OPERATION_IN_PROGRESS);
        if (state == State.FROZEN) throw Failure.of(ALREADY_FROZEN);
    }
    public void requireRegeneration(int expected) {
        requireVersion(expected);
        requireReady();
        if (regenerationUsed != 0) throw Failure.of(REGENERATION_EXHAUSTED);
    }
}
