package dev.worldcup.generation.engine;

import static dev.worldcup.candidate.CandidateModels.*;

import dev.worldcup.generation.GenerationInput;
import java.time.Instant;
import java.util.List;

/** Rich internal context stays behind CandidateEngine; public preview remains unchanged. */
public final class EngineModels {
    private EngineModels() {}
    public enum Decision { READY, CLARIFICATION_REQUIRED, UNSUPPORTED_REQUEST }
    public record ConstraintSpec(String id, String description, String sourceText, VerificationMode mode) {}
    public record BucketSpec(String id, String description, int quota) {}
    public record PlanProposal(Decision decision, String unit, boolean hobby, boolean groundingRequired,
                               List<ConstraintSpec> constraints, List<BucketSpec> coverage,
                               List<String> softPreferences) {
        public PlanProposal {
            constraints = List.copyOf(constraints);
            coverage = List.copyOf(coverage);
            softPreferences = List.copyOf(softPreferences);
        }
    }
    public record FixedPlan(GenerationInput input, Instant referenceTime, PlanProposal specification, Plan gatePlan) {}
    public record Proposal(String id, String name, String bucketId, List<String> tags,
                           String coreActivity, String description, String repeatability, String requirements) {
        public Proposal { tags = List.copyOf(tags); }
    }
    public record Batch(List<Proposal> candidates) {
        public Batch { candidates = List.copyOf(candidates); }
    }
    public record Finding(String code, List<String> candidateIds, String detail) {
        public Finding { candidateIds = List.copyOf(candidateIds); }
    }
    public record Review(Verdict planFaithful, Verdict comparable, Verdict noSemanticDuplicates,
                         Verdict candidateQuality, List<Assessment> assessments, List<Finding> findings) {
        public Review { assessments = List.copyOf(assessments); findings = List.copyOf(findings); }
    }
    public record FactCheck(String candidateId, String claimKey, Verdict verdict, String sourceUrl,
                            String excerpt, String requestApplicability) {}
    public record FactChecks(List<FactCheck> facts) {
        public FactChecks { facts = List.copyOf(facts); }
    }
    public record Grounding(List<GroundedFact> facts) {
        public Grounding { facts = List.copyOf(facts); }
        public static Grounding empty() { return new Grounding(List.of()); }
    }
    public record StageResult<T>(T value, String version) {}
    public record CallContext(String jobId, int attempt, String stage, Instant deadline) {}
}
