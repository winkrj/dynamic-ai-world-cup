package dev.worldcup.generation.engine;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.generation.engine.EngineModels.*;
import static org.assertj.core.api.Assertions.*;

import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.shared.Failure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StagedCandidateEngineTest {
    private final MovingClock clock = new MovingClock();
    private final FakeStages stages = new FakeStages();
    private final StagedCandidateEngine engine = new StagedCandidateEngine(stages, clock, Duration.ofSeconds(280));
    private GenerationInput input(int size) { return new GenerationInput("집에서 조용한 취미", size, "ko-KR", "Asia/Seoul"); }
    private CandidateEngine.Context context() { return new CandidateEngine.Context("test-job", 1, clock.instant().plusSeconds(300), List.of()); }
    private void qualityFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(Failure.class, e -> assertThat(e.code()).isEqualTo(Failure.Code.QUALITY_GATE_FAILED));
    }
    @ParameterizedTest @ValueSource(ints = {8, 16, 32}) void exposesOnlyValidatedExactSizeWithoutExtraRepair(int size) {
        var result = engine.generate(input(size), context());
        assertThat(result.candidates().candidates()).hasSize(size);
        assertThat(result.publicTitle()).isEqualTo(size + "강 선택 월드컵");
        assertThat(stages.calls).containsExactly("PLAN", "GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }
    @Test void invalidQuotaCannotReachGenerationOrRepair() {
        stages.quotaOffset = 1;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN");
    }
    @Test void inventedConstraintSourceCannotBecomeFixedPlan() {
        stages.source = "사용자가 말하지 않은 조건";
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN");
    }
    @Test void incompletePlanFailsWithoutRepairingAwayUserConditions() {
        stages.reviewFunction = (batch, count) -> new Review(Verdict.FAIL, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.repairs).isZero();
    }
    @Test void unknownHardConstraintRequiresRepairAndIndependentRecheck() {
        stages.reviewFunction = (batch, count) -> {
            var assessments = new ArrayList<>(assessments(batch));
            if (count == 1) assessments.set(0, new Assessment("c1", "quiet", Verdict.UNKNOWN));
            return new Review(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments, List.of());
        };
        var result = engine.generate(input(8), context());
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
        assertThat(result.candidates().candidates().getFirst().name()).contains("수정");
    }
    @Test void semanticDuplicateRepairChangesOnlyIdentifiedCandidate() {
        stages.reviewFunction = (batch, count) -> new Review(Verdict.PASS, Verdict.PASS,
                count == 1 ? Verdict.FAIL : Verdict.PASS, Verdict.PASS, assessments(batch),
                count == 1 ? List.of(new Finding("DUPLICATE_ACTIVITY", List.of("c2"), "Same core activity")) : List.of());
        engine.generate(input(8), context());
        assertThat(stages.lastReplacementIds).containsExactly("c2");
        assertThat(stages.calls).containsExactly("PLAN", "GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }
    @Test void qualityFailureAfterOneRepairNeverLoopsOrShrinksSize() {
        stages.reviewFunction = (batch, count) -> new Review(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.FAIL,
                assessments(batch), List.of(new Finding("FILLER", List.of("c1"), "Forced filler")));
        qualityFailure(() -> engine.generate(input(32), context()));
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.seenPlans).allMatch(p -> p.input().size() == 32);
        assertThat(stages.seenPlans).allSatisfy(p -> assertThat(p).isSameAs(stages.seenPlans.getFirst()));
    }
    @Test void repairCannotModifyAnUnflaggedCandidate() {
        stages.modifyUnflagged = true;
        stages.reviewFunction = (batch, count) -> new Review(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.FAIL,
                assessments(batch), List.of(new Finding("FILLER", List.of("c1"), "Forced filler")));
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.reviewCount).isEqualTo(1);
    }
    @Test void malformedInitialOutputCanBeReconstructedOnceUnderSamePlan() {
        stages.malformedGeneration = true;
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates()).hasSize(8);
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.lastReplacementIds).hasSize(8);
    }
    @Test void groundingCannotBeReplacedWithSemanticSelfApproval() {
        stages.groundingRequired = true;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).contains("GROUND_INITIAL", "GROUND_REPAIRED");
        assertThat(stages.repairs).isEqualTo(1);
    }
    @Test void deadlinePreventsAnotherPaidStageAfterSlowGeneration() {
        stages.slowGeneration = true;
        assertThatThrownBy(() -> engine.generate(input(8), context())).isInstanceOfSatisfying(Failure.class,
                e -> assertThat(e.code()).isEqualTo(Failure.Code.PROVIDER_UNAVAILABLE));
        assertThat(stages.calls).containsExactly("PLAN", "GENERATE");
    }
    @Test void interruptedThreadDoesNotStartAnyStage() {
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> engine.generate(input(8), context())).isInstanceOf(Failure.class); }
        finally { Thread.interrupted(); }
        assertThat(stages.calls).isEmpty();
    }
    private List<Assessment> assessments(Batch batch) { return batch.candidates().stream().map(c -> new Assessment(c.id(), "quiet", Verdict.PASS)).toList(); }
    private Batch batch(int size) { return new Batch(IntStream.rangeClosed(1, size).mapToObj(i -> new Proposal("c" + i, "합성 취미 " + i, "broad", List.of("합성"), "핵심 활동 " + i, "테스트 설명", "주 단위 연습", "준비 조건")).toList()); }
    private Proposal change(Proposal c) { return new Proposal(c.id(), c.name() + " 수정", c.bucketId(), c.tags(), c.coreActivity(), c.description(), c.repeatability(), c.requirements()); }
    private final class FakeStages implements EngineStages {
        final List<String> calls = new ArrayList<>(); final List<FixedPlan> seenPlans = new ArrayList<>();
        int quotaOffset, repairs, reviewCount; boolean modifyUnflagged, malformedGeneration, groundingRequired, slowGeneration;
        String source = "조용한"; List<String> lastReplacementIds;
        BiFunction<Batch, Integer, Review> reviewFunction = (batch, count) -> new Review(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), List.of());
        @Override public StageResult<PlanProposal> plan(GenerationInput input, List<CandidateEngine.Preference> history, Instant time, CallContext call) {
            calls.add(call.stage()); return new StageResult<>(new PlanProposal(Decision.READY, "취미", true, groundingRequired,
                    List.of(new ConstraintSpec("quiet", "조용해야 한다", source, VerificationMode.SEMANTIC_ESTIMATE)),
                    List.of(new BucketSpec("broad", "합성 테스트 활동", input.size() + quotaOffset)), List.of()), "fake-plan");
        }
        @Override public StageResult<Batch> generate(FixedPlan plan, CallContext call) {
            calls.add(call.stage()); seenPlans.add(plan);
            if (slowGeneration) clock.time = clock.time.plusSeconds(300);
            if (malformedGeneration) throw new InvalidModelOutput();
            return new StageResult<>(batch(plan.input().size()), "fake-generation");
        }
        @Override public Grounding ground(FixedPlan plan, Batch batch, CallContext call) { calls.add(call.stage()); seenPlans.add(plan); return Grounding.empty(); }
        @Override public StageResult<Review> review(FixedPlan plan, Batch batch, Grounding grounding, CallContext call) {
            calls.add(call.stage()); seenPlans.add(plan); return new StageResult<>(reviewFunction.apply(batch, ++reviewCount), "independent-fake-review");
        }
        @Override public StageResult<Batch> repair(FixedPlan plan, Batch original, List<String> ids, List<Finding> findings, CallContext call) {
            calls.add(call.stage()); seenPlans.add(plan); repairs++; lastReplacementIds = ids;
            var sourceBatch = original.candidates().isEmpty() ? batch(plan.input().size()) : original;
            return new StageResult<>(new Batch(sourceBatch.candidates().stream().map(c -> ids.contains(c.id()) || modifyUnflagged ? change(c) : c).toList()), "fake-repair");
        }
    }
    private static final class MovingClock extends Clock {
        Instant time = Instant.parse("2026-09-15T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}
