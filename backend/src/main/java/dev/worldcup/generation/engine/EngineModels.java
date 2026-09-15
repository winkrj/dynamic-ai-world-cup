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
    public record IntentSpec(String id, String coreActivity, String fit) {}
    public record BucketSpec(String id, String description, List<IntentSpec> intents) {
        public BucketSpec { intents = List.copyOf(intents); }
    }
    public record ActivityIntent(String id, String bucketId, String coreActivity, String fit) {}
    public record PlanProposal(Decision decision, String unit, boolean hobby, boolean groundingRequired,
                               List<ConstraintSpec> constraints, List<BucketSpec> coverage,
                               List<String> softPreferences) {
        public PlanProposal {
            constraints = List.copyOf(constraints);
            coverage = List.copyOf(coverage);
            softPreferences = List.copyOf(softPreferences);
        }
        /** Ownership comes from containment; the planner cannot reference a nonexistent bucket. */
        public List<ActivityIntent> intents() {
            return coverage.stream().flatMap(bucket -> bucket.intents().stream()
                    .map(intent -> new ActivityIntent(intent.id(), bucket.id(), intent.coreActivity(), intent.fit()))).toList();
        }
    }
    public enum InterpretationField { UNIT, HOBBY, CONSTRAINTS, SOFT_PREFERENCES, GROUNDING_REQUIRED }
    public record InterpretationFinding(InterpretationField field, String sourceText, String detail) {}
    /** Request interpretation only; candidate defects belong to their own review findings. */
    public record InterpretationReview(Verdict verdict, List<InterpretationFinding> findings) {
        public InterpretationReview { findings = List.copyOf(findings); }
    }
    public record IntentRejection(String intentId, String reason) {}
    /** Ranked, jointly distinct feasible intents; rejected items never enter the fixed allocation. */
    public record AllocationReview(InterpretationReview interpretation, Verdict comparable, Verdict noSemanticDuplicates,
                                   Verdict feasible, List<String> approvedIntentIds, List<IntentRejection> rejections) {
        public AllocationReview { approvedIntentIds = List.copyOf(approvedIntentIds); rejections = List.copyOf(rejections); }
    }
    /** Only rejected activities can be replaced; interpretation fields are not part of this output. */
    public record IntentRepairs(List<ActivityIntent> replacements) {
        public IntentRepairs { replacements = List.copyOf(replacements); }
    }
    public record FixedPlan(GenerationInput input, Instant referenceTime, PlanProposal specification, Plan gatePlan,
                            List<ActivityIntent> approvedIntents) {
        public FixedPlan { approvedIntents = List.copyOf(approvedIntents); }
    }
    public record Proposal(String id, String intentId, String name, String bucketId, List<String> tags,
                           String coreActivity, String description, String repeatability, String requirements) {
        public Proposal { tags = List.copyOf(tags); }
    }
    public record Batch(List<Proposal> candidates) {
        public Batch { candidates = List.copyOf(candidates); }
    }
    public record Finding(String code, List<String> candidateIds, String detail) {
        public Finding { candidateIds = List.copyOf(candidateIds); }
    }
    public record Review(InterpretationReview interpretation, Verdict comparable, Verdict noSemanticDuplicates,
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
