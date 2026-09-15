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
import java.util.function.Function;
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
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }
    @Test void abstractCoverageWithoutEnoughConcreteIntentsCannotReachGenerationOrRepair() {
        stages.intentCountOffset = -1;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN");
    }
    @Test void fewerThanNIndependentlyApprovedIntentsFailsBeforeDetailedGeneration() {
        stages.allocationFunction = plan -> allocation(plan.intents().stream().limit(7).map(ActivityIntent::id).toList());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
    }
    @Test void independentAllocationCannotInventOrRepeatIds() {
        stages.allocationFunction = plan -> allocation(List.of("i1", "i2", "i3", "i4", "i5", "i6", "i7", "invented"));
        qualityFailure(() -> engine.generate(input(8), context()));
        stages.allocationFunction = plan -> allocation(List.of("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i7"));
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).doesNotContain("GENERATE", "REPAIR");
    }
    @Test void unknownAllocationNeverFreezesEvenWithEnoughIds() {
        stages.allocationFunction = plan -> new AllocationReview(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.UNKNOWN,
                plan.intents().stream().map(ActivityIntent::id).toList());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
    }
    @Test void quotaIsDerivedFromApprovedActivitiesNotRejectedReadingVariants() {
        stages.readingVariants = true;
        stages.allocationFunction = plan -> allocation(plan.intents().stream()
                .filter(i -> !List.of("i2", "i3").contains(i.id())).map(ActivityIntent::id).toList());
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates()).hasSize(8);
        assertThat(stages.seenPlans.getFirst().gatePlan().coverage()).containsExactly(
                new CoverageBucket("reading", 1), new CoverageBucket("broad", 7));
        assertThat(stages.seenPlans.getFirst().approvedIntents()).noneMatch(i -> List.of("i2", "i3").contains(i.id()));
    }
    @Test void generationCannotChangeAnApprovedActivityBehindItsId() {
        stages.changeCore = true;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).doesNotContain("REVIEW_INITIAL", "REVIEW_REPAIRED");
        assertThat(stages.repairs).isEqualTo(1);
    }
    @Test void repairMayUseAnUnusedApprovedAlternativeWithoutChangingQuotaOrOtherCandidates() {
        stages.useAlternative = true;
        stages.reviewFunction = (batch, count) -> new Review(Verdict.PASS, Verdict.PASS, Verdict.PASS,
                count == 1 ? Verdict.FAIL : Verdict.PASS, assessments(batch),
                count == 1 ? List.of(new Finding("FILLER", List.of("c1"), "Detail is unsuitable")) : List.of());
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates().getFirst().name()).isEqualTo("대체 활동");
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.repairs).isEqualTo(1);
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
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
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
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE");
    }
    @Test void interruptedThreadDoesNotStartAnyStage() {
        Thread.currentThread().interrupt();
        try { assertThatThrownBy(() -> engine.generate(input(8), context())).isInstanceOf(Failure.class); }
        finally { Thread.interrupted(); }
        assertThat(stages.calls).isEmpty();
    }
    private List<Assessment> assessments(Batch batch) { return batch.candidates().stream().map(c -> new Assessment(c.id(), "quiet", Verdict.PASS)).toList(); }
    private AllocationReview allocation(List<String> ids) { return new AllocationReview(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.PASS, ids); }
    private Batch batch(FixedPlan plan) { return new Batch(IntStream.rangeClosed(1, plan.input().size()).mapToObj(i -> {
        var intent = plan.approvedIntents().get(i - 1);
        return new Proposal("c" + i, intent.id(), "합성 취미 " + i, intent.bucketId(), List.of("합성"),
                stages.changeCore ? "승인받지 않은 활동" : intent.coreActivity(), "테스트 설명", "주 단위 연습", "준비 조건");
    }).toList()); }
    private Proposal change(Proposal c) { return new Proposal(c.id(), c.intentId(), c.name() + " 수정", c.bucketId(), c.tags(), c.coreActivity(), c.description(), c.repeatability(), c.requirements()); }
    private final class FakeStages implements EngineStages {
        final List<String> calls = new ArrayList<>(); final List<FixedPlan> seenPlans = new ArrayList<>();
        int intentCountOffset = 2, repairs, reviewCount;
        boolean modifyUnflagged, malformedGeneration, groundingRequired, slowGeneration, readingVariants, changeCore, useAlternative;
        String source = "조용한"; List<String> lastReplacementIds;
        Function<PlanProposal, AllocationReview> allocationFunction = plan -> allocation(plan.intents().stream().map(ActivityIntent::id).toList());
        BiFunction<Batch, Integer, Review> reviewFunction = (batch, count) -> new Review(Verdict.PASS, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), List.of());
        @Override public StageResult<PlanProposal> plan(GenerationInput input, List<CandidateEngine.Preference> history, Instant time, CallContext call) {
            var intents = IntStream.rangeClosed(1, input.size() + intentCountOffset)
                    .mapToObj(i -> new IntentSpec("i" + i, "핵심 활동 " + i, "합성 적합성 설명")).toList();
            calls.add(call.stage()); return new StageResult<>(new PlanProposal(Decision.READY, "취미", true, groundingRequired,
                    List.of(new ConstraintSpec("quiet", "조용해야 한다", source, VerificationMode.SEMANTIC_ESTIMATE)),
                    readingVariants ? List.of(new BucketSpec("reading", "독서", intents.subList(0, 3)), new BucketSpec("broad", "합성 테스트 활동", intents.subList(3, intents.size())))
                            : List.of(new BucketSpec("broad", "합성 테스트 활동", intents)), List.of()), "fake-plan");
        }
        @Override public StageResult<AllocationReview> allocate(GenerationInput input, Instant time, PlanProposal proposal, CallContext call) {
            calls.add(call.stage()); return new StageResult<>(allocationFunction.apply(proposal), "independent-allocation");
        }
        @Override public StageResult<Batch> generate(FixedPlan plan, CallContext call) {
            calls.add(call.stage()); seenPlans.add(plan);
            if (slowGeneration) clock.time = clock.time.plusSeconds(300);
            if (malformedGeneration) throw new InvalidModelOutput();
            return new StageResult<>(batch(plan), "fake-generation");
        }
        @Override public Grounding ground(FixedPlan plan, Batch batch, CallContext call) { calls.add(call.stage()); seenPlans.add(plan); return Grounding.empty(); }
        @Override public StageResult<Review> review(FixedPlan plan, Batch batch, Grounding grounding, CallContext call) {
            calls.add(call.stage()); seenPlans.add(plan); return new StageResult<>(reviewFunction.apply(batch, ++reviewCount), "independent-fake-review");
        }
        @Override public StageResult<Batch> repair(FixedPlan plan, Batch original, List<String> ids, List<Finding> findings, CallContext call) {
            calls.add(call.stage()); seenPlans.add(plan); repairs++; lastReplacementIds = ids;
            var sourceBatch = original.candidates().isEmpty() ? batch(plan) : original;
            return new StageResult<>(new Batch(sourceBatch.candidates().stream().map(c -> {
                if (useAlternative && ids.contains(c.id())) {
                    var alternative = plan.approvedIntents().get(plan.input().size());
                    return new Proposal(c.id(), alternative.id(), "대체 활동", alternative.bucketId(), c.tags(), alternative.coreActivity(), c.description(), c.repeatability(), c.requirements());
                }
                return ids.contains(c.id()) || modifyUnflagged ? change(c) : c;
            }).toList()), "fake-repair");
        }
    }
    private static final class MovingClock extends Clock {
        Instant time = Instant.parse("2026-09-15T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}
