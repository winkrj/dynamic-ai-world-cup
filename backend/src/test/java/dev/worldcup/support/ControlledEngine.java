package dev.worldcup.support;

import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.infrastructure.DevelopmentCandidateEngine;
import dev.worldcup.shared.Failure;
import java.util.concurrent.atomic.AtomicInteger;

public class ControlledEngine implements CandidateEngine {
    public final AtomicInteger calls = new AtomicInteger();
    public Failure.Code failNext;
    public boolean wrongSize;
    public Context lastContext;
    @Override public Generated generate(GenerationInput input, Context context) {
        calls.incrementAndGet();
        lastContext = context;
        if (failNext != null) {
            var failure = failNext; failNext = null;
            throw Failure.of(failure);
        }
        return new DevelopmentCandidateEngine().generate(wrongSize ? new GenerationInput(input.prompt(), input.size() == 8 ? 16 : 8,
                input.locale(), input.timezone()) : input, context);
    }
    public void reset() { calls.set(0); failNext = null; wrongSize = false; lastContext = null; }
}
