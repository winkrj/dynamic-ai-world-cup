package dev.worldcup.candidate;

import static dev.worldcup.candidate.CandidateModels.*;

import java.net.URI;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Distinguishes structural acceptance from the legacy independently reviewed policy. */
public final class CandidateQualityGate {
    public enum AcceptancePolicy { INDEPENDENT_REVIEW, FAST_BEST_EFFORT }
    public enum Code {
        INVALID_SCHEMA, INVALID_PLAN, COUNT_MISMATCH, DUPLICATE_ID, DUPLICATE_NAME,
        UNIT_MISMATCH, COVERAGE_MISMATCH, HARD_CONSTRAINT_UNVERIFIED,
        GROUNDING_UNVERIFIED, INVALID_EVIDENCE, SEMANTIC_REVIEW_FAILED
    }
    public record Issue(Code code, String candidateId, String detail) {}

    /** Gate-issued acceptance, not a promise of independent review under every policy. */
    public static final class ValidatedSet {
        private final Plan plan;
        private final List<Candidate> candidates;
        private final AcceptancePolicy policy;
        private ValidatedSet(Plan plan, List<Candidate> candidates) {
            this(plan, candidates, AcceptancePolicy.INDEPENDENT_REVIEW);
        }
        private ValidatedSet(Plan plan, List<Candidate> candidates, AcceptancePolicy policy) {
            this.plan = plan;
            this.candidates = List.copyOf(candidates);
            this.policy = policy;
        }
        public Plan plan() { return plan; }
        public List<Candidate> candidates() { return candidates; }
        public AcceptancePolicy policy() { return policy; }
    }
    public record Result(List<Issue> issues, Optional<ValidatedSet> validated) {
        public Result { issues = List.copyOf(issues); }
        public boolean passed() { return validated.isPresent(); }
    }
    private final Duration maximumEvidenceAge;

    public CandidateQualityGate(Duration maximumEvidenceAge) {
        if (maximumEvidenceAge == null || maximumEvidenceAge.isNegative() || maximumEvidenceAge.isZero()) {
            throw new IllegalArgumentException("Evidence age must be positive");
        }
        this.maximumEvidenceAge = maximumEvidenceAge;
    }

    public Result validate(Plan plan, List<Candidate> candidates, Evidence evidence, Instant now) {
        if (plan == null || candidates == null || evidence == null || now == null) {
            return new Result(List.of(issue(Code.INVALID_SCHEMA, null, "Missing validation input")), Optional.empty());
        }
        // Freeze caller-owned containers before validation to prevent unchecked replacements.
        candidates = new ArrayList<>(candidates);
        List<Issue> issues = structuralIssues(plan, candidates);
        Map<String, VerificationMode> constraints = new HashMap<>();
        plan.hardConstraints().forEach(c -> constraints.put(c.id(), c.mode()));
        Set<String> ids = new HashSet<>();
        candidates.stream().filter(java.util.Objects::nonNull).forEach(c -> ids.add(c.id()));

        Map<String, Map<String, Verdict>> assessmentMap = new HashMap<>();
        for (Assessment assessment : evidence.assessments()) {
            if (!ids.contains(assessment.candidateId()) || !constraints.containsKey(assessment.constraintId()) || assessment.verdict() == null) {
                issues.add(issue(Code.INVALID_EVIDENCE, assessment.candidateId(), "Unknown assessment target or verdict"));
            }
            Map<String, Verdict> byConstraint = assessmentMap.computeIfAbsent(assessment.candidateId(), ignored -> new HashMap<>());
            if (byConstraint.containsKey(assessment.constraintId())) issues.add(issue(Code.INVALID_EVIDENCE, assessment.candidateId(), "Duplicate assessment"));
            byConstraint.put(assessment.constraintId(), assessment.verdict());
        }
        for (GroundedFact fact : evidence.facts()) {
            if (!ids.contains(fact.candidateId()) || !("availability".equals(fact.claimKey()) || constraints.containsKey(fact.claimKey()))) {
                issues.add(issue(Code.INVALID_EVIDENCE, fact.candidateId(), "Unknown grounding target"));
            }
        }
        for (String id : ids) {
            for (Map.Entry<String, VerificationMode> constraint : constraints.entrySet()) {
                Verdict verdict = assessmentMap.getOrDefault(id, Map.of()).get(constraint.getKey());
                if (verdict != Verdict.PASS) issues.add(issue(Code.HARD_CONSTRAINT_UNVERIFIED, id, constraint.getKey()));
                if (constraint.getValue() == VerificationMode.GROUNDED_FACT && !verifiedFact(evidence, id, constraint.getKey(), now)) {
                    issues.add(issue(Code.GROUNDING_UNVERIFIED, id, constraint.getKey()));
                }
            }
            if (plan.groundingRequired() && !verifiedFact(evidence, id, "availability", now)) issues.add(issue(Code.GROUNDING_UNVERIFIED, id, "availability"));
        }
        if (evidence.comparable() != Verdict.PASS || evidence.noSemanticDuplicates() != Verdict.PASS || blank(evidence.reviewerVersion())) {
            issues.add(issue(Code.SEMANTIC_REVIEW_FAILED, null, "Independent comparability/semantic-duplicate review required"));
        }
        return new Result(issues, issues.isEmpty() ? Optional.of(new ValidatedSet(plan, candidates)) : Optional.empty());
    }

    /** TD-54: server checks structure; semantic suitability remains best-effort, never fabricated PASS evidence. */
    public Result acceptBestEffort(Plan plan, List<Candidate> candidates) {
        if (plan == null || candidates == null) {
            return new Result(List.of(issue(Code.INVALID_SCHEMA, null, "Missing validation input")), Optional.empty());
        }
        candidates = new ArrayList<>(candidates);
        var issues = structuralIssues(plan, candidates);
        if (plan.groundingRequired() || plan.hardConstraints().stream().anyMatch(c -> c.mode() == VerificationMode.GROUNDED_FACT)) {
            issues.add(issue(Code.GROUNDING_UNVERIFIED, null, "Fast policy cannot attest external facts"));
        }
        return new Result(issues, issues.isEmpty()
                ? Optional.of(new ValidatedSet(plan, candidates, AcceptancePolicy.FAST_BEST_EFFORT)) : Optional.empty());
    }

    private List<Issue> structuralIssues(Plan plan, List<Candidate> candidates) {
        List<Issue> issues = new ArrayList<>();
        if (!Set.of(8, 16, 32).contains(plan.size()) || blank(plan.unit()) || plan.unit().length() > 200) {
            issues.add(issue(Code.INVALID_PLAN, null, "Expected size 8/16/32 and one candidate unit"));
        }
        if (candidates.size() != plan.size()) issues.add(issue(Code.COUNT_MISMATCH, null, "Expected " + plan.size() + " candidates"));

        Map<String, Integer> quotas = new HashMap<>();
        long quotaTotal = 0;
        for (CoverageBucket bucket : plan.coverage()) {
            if (blank(bucket.id()) || bucket.quota() <= 0 || quotas.putIfAbsent(bucket.id(), bucket.quota()) != null) {
                issues.add(issue(Code.INVALID_PLAN, null, "Invalid or duplicate coverage bucket"));
            }
            quotaTotal += bucket.quota();
        }
        if (quotas.isEmpty() || quotaTotal != plan.size()) issues.add(issue(Code.INVALID_PLAN, null, "Coverage quotas must total requested size"));

        Map<String, VerificationMode> constraints = new HashMap<>();
        for (HardConstraint constraint : plan.hardConstraints()) {
            if (blank(constraint.id()) || "availability".equals(constraint.id()) || constraint.mode() == null
                    || constraints.putIfAbsent(constraint.id(), constraint.mode()) != null) {
                issues.add(issue(Code.INVALID_PLAN, null, "Invalid, reserved or duplicate constraint ID"));
            }
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        Map<String, Integer> actualCoverage = new HashMap<>();
        for (Candidate candidate : candidates) {
            if (candidate == null) {
                issues.add(issue(Code.INVALID_SCHEMA, null, "Null candidate"));
                continue;
            }
            String id = candidate.id();
            if (blank(id) || blank(candidate.name()) || candidate.name().length() > 100
                    || candidate.tags().size() > 2 || candidate.tags().stream().anyMatch(t -> blank(t) || t.length() > 40)
                    || (candidate.imageUrl() != null && !httpsUrl(candidate.imageUrl()))) {
                issues.add(issue(Code.INVALID_SCHEMA, id, "Invalid display candidate"));
            }
            if (!ids.add(id)) issues.add(issue(Code.DUPLICATE_ID, id, "Duplicate candidate ID"));
            if (!names.add(normalize(candidate.name()))) issues.add(issue(Code.DUPLICATE_NAME, id, "Duplicate normalized name"));
            if (!normalize(plan.unit()).equals(normalize(candidate.unit()))) issues.add(issue(Code.UNIT_MISMATCH, id, "Candidate must use the fixed plan unit"));
            if (!quotas.containsKey(candidate.bucketId())) issues.add(issue(Code.COVERAGE_MISMATCH, id, "Unknown coverage bucket"));
            actualCoverage.merge(candidate.bucketId(), 1, Integer::sum);
        }
        quotas.forEach((id, count) -> {
            if (!count.equals(actualCoverage.getOrDefault(id, 0))) issues.add(issue(Code.COVERAGE_MISMATCH, null, "Quota not met: " + id));
        });

        return issues;
    }

    private boolean verifiedFact(Evidence evidence, String id, String claim, Instant now) {
        return evidence.facts().stream().anyMatch(fact -> java.util.Objects.equals(id, fact.candidateId())
                && claim.equals(fact.claimKey()) && fact.verdict() == Verdict.PASS
                && httpsUrl(fact.sourceUrl()) && !blank(fact.excerpt())
                && fact.checkedAt() != null && !fact.checkedAt().isAfter(now)
                && fact.checkedAt().isAfter(now.minus(maximumEvidenceAge))
                && fact.validUntil() != null && fact.validUntil().isAfter(now));
    }
    private static Issue issue(Code code, String id, String detail) { return new Issue(code, id, detail); }
    private static boolean blank(String value) { return normalize(value).isEmpty(); }
    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private static boolean httpsUrl(String value) {
        if (blank(value)) return false;
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
