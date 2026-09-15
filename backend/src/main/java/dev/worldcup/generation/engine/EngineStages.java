package dev.worldcup.generation.engine;

import static dev.worldcup.generation.engine.EngineModels.*;

import dev.worldcup.generation.CandidateEngine.Preference;
import dev.worldcup.generation.GenerationInput;
import java.time.Instant;
import java.util.List;

/** Each review/grounding call is independent of the generator's response/conversation. */
public interface EngineStages {
    StageResult<PlanProposal> plan(GenerationInput input, List<Preference> history, Instant referenceTime, CallContext call);
    StageResult<AllocationReview> allocate(GenerationInput input, Instant referenceTime, PlanProposal proposal, CallContext call);
    StageResult<Batch> generate(FixedPlan plan, CallContext call);
    Grounding ground(FixedPlan plan, Batch candidates, CallContext call);
    StageResult<Review> review(FixedPlan plan, Batch candidates, Grounding grounding, CallContext call);
    StageResult<Batch> repair(FixedPlan plan, Batch original, List<String> replacementIds,
                              List<Finding> findings, CallContext call);
}
