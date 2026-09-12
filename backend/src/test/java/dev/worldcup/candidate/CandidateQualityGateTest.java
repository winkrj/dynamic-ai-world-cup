package dev.worldcup.candidate;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.candidate.CandidateQualityGate.Code.*;
import static org.assertj.core.api.Assertions.*;

import dev.worldcup.api.PreviewResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CandidateQualityGateTest {
    private static final Instant NOW = Instant.parse("2026-09-12T00:00:00Z");
    private final CandidateQualityGate gate = new CandidateQualityGate(Duration.ofHours(24));

    private Plan plan(int size) {
        return new Plan(size, "취미 활동", false,
                List.of(new HardConstraint("solo", VerificationMode.SEMANTIC_ESTIMATE)),
                List.of(new CoverageBucket("creative", size / 2), new CoverageBucket("reflective", size / 2)));
    }
    private List<Candidate> candidates(int size) {
        return new ArrayList<>(IntStream.range(0, size).mapToObj(i -> new Candidate("c-" + i, "취미 " + i,
                "취미 활동", i < size / 2 ? "creative" : "reflective", List.of("혼자"), null)).toList());
    }
    private Evidence evidence(int size) {
        return new Evidence(IntStream.range(0, size).mapToObj(i -> new Assessment("c-" + i, "solo", Verdict.PASS)).toList(),
                List.of(), Verdict.PASS, Verdict.PASS, "synthetic-review-v1");
    }
    private void rejects(Plan plan, List<Candidate> candidates, Evidence evidence, CandidateQualityGate.Code code) {
        var result = gate.validate(plan, candidates, evidence, NOW);
        assertThat(result.passed()).isFalse();
        assertThat(result.validated()).isEmpty();
        assertThat(result.issues()).extracting(CandidateQualityGate.Issue::code).contains(code);
    }
    private Candidate replace(Candidate c, String id, String name, String unit, String bucket) {
        return new Candidate(id, name, unit, bucket, c.tags(), c.imageUrl());
    }

    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void acceptsExactTournamentSizesAndEnablesPreview(int size) {
        var result = gate.validate(plan(size), candidates(size), evidence(size), NOW);
        assertThat(result.issues()).isEmpty();
        var preview = PreviewResponse.from("draft", 1, 1, result.validated().orElseThrow());
        assertThat(preview.size()).isEqualTo(size);
        assertThat(preview.candidates()).hasSize(size);
        assertThat(preview.status()).isEqualTo("READY");
    }
    @Test void rejectsMissingInput() { rejects(null, candidates(8), evidence(8), INVALID_SCHEMA); }
    @Test void rejectsNullCandidate() {
        var list = candidates(8); list.set(0, null);
        rejects(plan(8), list, evidence(8), INVALID_SCHEMA);
    }
    @Test void neverShrinks32To16() { rejects(plan(32), candidates(16), evidence(16), COUNT_MISMATCH); }
    @Test void rejectsUnsupportedSize() { rejects(plan(12), candidates(12), evidence(12), INVALID_PLAN); }
    @Test void rejectsDuplicateIdEvenWithDifferentNames() {
        var list = candidates(8); var c = list.get(1);
        list.set(1, replace(c, "c-0", c.name(), c.unit(), c.bucketId()));
        rejects(plan(8), list, evidence(8), DUPLICATE_ID);
    }
    @Test void detectsUnicodeCaseAndWhitespaceNameDuplicates() {
        var list = candidates(8);
        list.set(0, replace(list.get(0), "c-0", " Ｃｒａｆｔ　 Studio ", "취미 활동", "creative"));
        list.set(1, replace(list.get(1), "c-1", "craft   studio", "취미 활동", "creative"));
        rejects(plan(8), list, evidence(8), DUPLICATE_NAME);
    }
    @Test void rejectsMixedCandidateUnit() {
        var list = candidates(8); var c = list.get(0);
        list.set(0, replace(c, c.id(), c.name(), "지역", c.bucketId()));
        rejects(plan(8), list, evidence(8), UNIT_MISMATCH);
    }
    @Test void rejectsCoverageConcentrationEvenWhenCountIsCorrect() {
        var list = candidates(8); var c = list.get(7);
        list.set(7, replace(c, c.id(), c.name(), c.unit(), "creative"));
        rejects(plan(8), list, evidence(8), COVERAGE_MISMATCH);
    }
    @Test void rejectsUnknownCoverageBucket() {
        var list = candidates(8); var c = list.get(7);
        list.set(7, replace(c, c.id(), c.name(), c.unit(), "made-up"));
        rejects(plan(8), list, evidence(8), COVERAGE_MISMATCH);
    }
    @Test void rejectsInvalidCoveragePlanRatherThanRepairingItsQuota() {
        var original = plan(8);
        var invalid = new Plan(8, original.unit(), false, original.hardConstraints(), List.of(new CoverageBucket("creative", 7)));
        rejects(invalid, candidates(8), evidence(8), INVALID_PLAN);
    }
    @Test void missingHardAssessmentIsNotAssumedToPass() {
        var ev = evidence(8);
        rejects(plan(8), candidates(8), new Evidence(ev.assessments().subList(1, 8), List.of(), Verdict.PASS, Verdict.PASS, "test"), HARD_CONSTRAINT_UNVERIFIED);
    }
    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN"})
    void rejectsUnmetOrUnknownHardConstraint(String verdict) {
        var assessments = new ArrayList<>(evidence(8).assessments());
        assessments.set(0, new Assessment("c-0", "solo", Verdict.valueOf(verdict)));
        rejects(plan(8), candidates(8), new Evidence(assessments, List.of(), Verdict.PASS, Verdict.PASS, "test"), HARD_CONSTRAINT_UNVERIFIED);
    }
    @Test void rejectsDuplicateAssessmentInsteadOfLettingLastPassWin() {
        var assessments = new ArrayList<>(evidence(8).assessments());
        assessments.add(new Assessment("c-0", "solo", Verdict.PASS));
        rejects(plan(8), candidates(8), new Evidence(assessments, List.of(), Verdict.PASS, Verdict.PASS, "test"), INVALID_EVIDENCE);
    }
    @Test void rejectsEvidenceForDifferentCandidate() {
        var assessments = new ArrayList<>(evidence(8).assessments());
        assessments.add(new Assessment("not-in-set", "solo", Verdict.PASS));
        rejects(plan(8), candidates(8), new Evidence(assessments, List.of(), Verdict.PASS, Verdict.PASS, "test"), INVALID_EVIDENCE);
    }
    @Test void stableConceptsNeedNoSearchWhenIndependentSemanticReviewPasses() {
        assertThat(gate.validate(plan(8), candidates(8), evidence(8), NOW).passed()).isTrue();
    }
    private Plan groundedPlan() {
        return new Plan(8, "취미 활동", true,
                List.of(new HardConstraint("budget", VerificationMode.GROUNDED_FACT)), plan(8).coverage());
    }
    private Evidence groundedEvidence() {
        var assessments = IntStream.range(0, 8).mapToObj(i -> new Assessment("c-" + i, "budget", Verdict.PASS)).toList();
        var facts = new ArrayList<GroundedFact>();
        for (int i = 0; i < 8; i++) for (String claim : List.of("budget", "availability")) {
            facts.add(new GroundedFact("c-" + i, claim, Verdict.PASS, "https://example.invalid/fixture", "SYNTHETIC evidence, not a real source",
                    NOW.minusSeconds(60), NOW.plusSeconds(3600)));
        }
        return new Evidence(assessments, facts, Verdict.PASS, Verdict.PASS, "synthetic-review-v1");
    }
    @Test void acceptsGroundingAdapterEvidenceWithinFreshnessWindow() {
        assertThat(gate.validate(groundedPlan(), candidates(8), groundedEvidence(), NOW).passed()).isTrue();
    }
    @Test void hardFactNeedsItsOwnEvidenceNotJustGeneralAvailability() {
        var ev = groundedEvidence();
        var availabilityOnly = ev.facts().stream().filter(f -> f.claimKey().equals("availability")).toList();
        rejects(groundedPlan(), candidates(8), new Evidence(ev.assessments(), availabilityOnly, Verdict.PASS, Verdict.PASS, "test"), GROUNDING_UNVERIFIED);
    }
    @ParameterizedTest @ValueSource(strings = {"stale", "future", "expired", "unverified", "no-source", "no-excerpt", "http"})
    void rejectsUnusableGroundedEvidence(String problem) {
        var ev = groundedEvidence(); var facts = new ArrayList<>(ev.facts()); var f = facts.getFirst();
        facts.set(0, new GroundedFact(f.candidateId(), f.claimKey(), problem.equals("unverified") ? Verdict.UNKNOWN : f.verdict(),
                problem.equals("no-source") ? null : problem.equals("http") ? "http://example.invalid" : f.sourceUrl(),
                problem.equals("no-excerpt") ? " " : f.excerpt(),
                problem.equals("stale") ? NOW.minus(Duration.ofHours(24)) : problem.equals("future") ? NOW.plusSeconds(1) : f.checkedAt(),
                problem.equals("expired") ? NOW : f.validUntil()));
        rejects(groundedPlan(), candidates(8), new Evidence(ev.assessments(), facts, Verdict.PASS, Verdict.PASS, "test"), GROUNDING_UNVERIFIED);
    }
    @Test void realWorldPlanCannotPassWithoutAvailabilityGrounding() {
        var original = plan(8);
        rejects(new Plan(8, original.unit(), true, original.hardConstraints(), original.coverage()), candidates(8), evidence(8), GROUNDING_UNVERIFIED);
    }
    @Test void rejectsSemanticDuplicatesEvenWithDistinctNames() {
        var ev = evidence(8);
        rejects(plan(8), candidates(8), new Evidence(ev.assessments(), List.of(), Verdict.PASS, Verdict.FAIL, "test"), SEMANTIC_REVIEW_FAILED);
    }
    @Test void rejectsMissingIndependentReviewer() {
        var ev = evidence(8);
        rejects(plan(8), candidates(8), new Evidence(ev.assessments(), List.of(), Verdict.PASS, Verdict.PASS, ""), SEMANTIC_REVIEW_FAILED);
    }
    @Test void rejectsThreeDisplayTags() {
        var list = candidates(8); var c = list.getFirst();
        list.set(0, new Candidate(c.id(), c.name(), c.unit(), c.bucketId(), List.of("a", "b", "c"), null));
        rejects(plan(8), list, evidence(8), INVALID_SCHEMA);
    }
    @Test void rejectsExecutableImageUrl() {
        var list = candidates(8); var c = list.getFirst();
        list.set(0, new Candidate(c.id(), c.name(), c.unit(), c.bucketId(), List.of(), "javascript:alert(1)"));
        rejects(plan(8), list, evidence(8), INVALID_SCHEMA);
    }
    @Test void rejectsUnicodeWhitespaceOnlyName() {
        var list = candidates(8); var c = list.getFirst();
        list.set(0, replace(c, c.id(), "\u00a0\u202f", c.unit(), c.bucketId()));
        rejects(plan(8), list, evidence(8), INVALID_SCHEMA);
    }
    @Test void rejectsUnicodeWhitespaceOnlyTag() {
        var list = candidates(8); var c = list.getFirst();
        list.set(0, new Candidate(c.id(), c.name(), c.unit(), c.bucketId(), List.of("\u00a0"), null));
        rejects(plan(8), list, evidence(8), INVALID_SCHEMA);
    }
    @Test void rejectsImageSchemeCasingThatViolatesPublicContract() {
        var list = candidates(8); var c = list.getFirst();
        list.set(0, new Candidate(c.id(), c.name(), c.unit(), c.bucketId(), c.tags(), "HTTPS://example.invalid/image.png"));
        rejects(plan(8), list, evidence(8), INVALID_SCHEMA);
    }
    @Test void validatedSetCannotBeChangedByCallerAfterPassing() {
        var list = candidates(8);
        var accepted = gate.validate(plan(8), list, evidence(8), NOW).validated().orElseThrow();
        list.clear();
        assertThat(accepted.candidates()).hasSize(8);
        assertThatThrownBy(() -> accepted.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void modelContainersRejectNullsAtSchemaBoundary() {
        assertThatThrownBy(() -> new Candidate("id", "name", "unit", "bucket", null, null)).isInstanceOf(NullPointerException.class);
    }
}
