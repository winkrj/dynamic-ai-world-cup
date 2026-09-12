package dev.worldcup.candidate;

import java.time.Instant;
import java.util.List;

/** Internal models; never expose these assessment fields as public DTOs. */
public final class CandidateModels {
    private CandidateModels() {}
    public enum VerificationMode { SEMANTIC_ESTIMATE, GROUNDED_FACT }
    public enum Verdict { PASS, FAIL, UNKNOWN }
    public record HardConstraint(String id, VerificationMode mode) {}
    public record CoverageBucket(String id, int quota) {}
    public record Plan(int size, String unit, boolean groundingRequired,
                       List<HardConstraint> hardConstraints, List<CoverageBucket> coverage) {
        public Plan {
            hardConstraints = List.copyOf(hardConstraints);
            coverage = List.copyOf(coverage);
        }
    }
    public record Candidate(String id, String name, String unit, String bucketId,
                            List<String> tags, String imageUrl) {
        public Candidate { tags = List.copyOf(tags); }
    }
    public record Assessment(String candidateId, String constraintId, Verdict verdict) {}
    /** claimKey is a hard constraint ID, or "availability" for real-world validity. */
    public record GroundedFact(String candidateId, String claimKey, Verdict verdict,
                               String sourceUrl, String excerpt, Instant checkedAt, Instant validUntil) {}
    /** Supplied by a separate validator/grounding adapter, never generator self-scores. */
    public record Evidence(List<Assessment> assessments, List<GroundedFact> facts,
                           Verdict comparable, Verdict noSemanticDuplicates, String reviewerVersion) {
        public Evidence {
            assessments = List.copyOf(assessments);
            facts = List.copyOf(facts);
        }
    }
}
