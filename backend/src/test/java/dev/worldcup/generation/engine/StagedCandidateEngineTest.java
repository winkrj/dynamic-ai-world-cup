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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class StagedCandidateEngineTest {
    private final MovingClock clock = new MovingClock();
    private final FakeStages stages = new FakeStages();
    private final StagedCandidateEngine engine = new StagedCandidateEngine(stages, clock, Duration.ofSeconds(280));
    private InterpretationReview faithful() { return new InterpretationReview(Verdict.PASS, List.of()); }
    private InterpretationReview unfaithful() {
        return new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(InterpretationField.CONSTRAINTS, "조용한", "조건 해석 불일치")));
    }
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
    @Test void stillTooFewAfterSingleIntentRepairFailsBeforeDetailedGeneration() {
        stages.allocationFunction = plan -> allocation(plan, plan.intents().stream().limit(7).map(ActivityIntent::id).toList());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "REPAIR_INTENTS", "ALLOCATE_REPAIRED");
        assertThat(stages.intentRepairs).isEqualTo(1);
    }
    @Test void independentAllocationCannotInventOrRepeatIds() {
        stages.allocationFunction = plan -> allocation(plan, List.of("i1", "i2", "i3", "i4", "i5", "i6", "i7", "invented"));
        qualityFailure(() -> engine.generate(input(8), context()));
        stages.allocationFunction = plan -> allocation(plan, List.of("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i7"));
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).doesNotContain("GENERATE", "REPAIR", "REPAIR_INTENTS");
    }
    @Test void unknownAllocationNeverFreezesEvenWithEnoughIds() {
        stages.allocationFunction = plan -> new AllocationReview(faithful(), Verdict.PASS, Verdict.PASS, Verdict.UNKNOWN,
                plan.intents().stream().map(ActivityIntent::id).toList(), List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
    }
    @Test void insufficientActivitiesArePatchedOnceAndIndependentlyRecheckedBeforeFreeze() {
        rejectLastIntentUntilRepaired();
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates()).hasSize(8);
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "REPAIR_INTENTS", "ALLOCATE_REPAIRED", "GENERATE", "REVIEW_INITIAL");
        assertThat(stages.intentRepairs).isEqualTo(1);
        assertThat(stages.repairs).isZero();
        var fixed = stages.seenPlans.getFirst().specification();
        assertThat(fixed.unit()).isEqualTo(stages.originalPlan.unit());
        assertThat(fixed.constraints()).isEqualTo(stages.originalPlan.constraints());
        assertThat(fixed.hobby()).isEqualTo(stages.originalPlan.hobby());
        assertThat(fixed.groundingRequired()).isEqualTo(stages.originalPlan.groundingRequired());
        assertThat(fixed.softPreferences()).isEqualTo(stages.originalPlan.softPreferences());
        assertThat(fixed.coverage().getFirst().description()).isEqualTo(stages.originalPlan.coverage().getFirst().description());
        assertThat(fixed.intents().subList(0, 7)).isEqualTo(stages.originalPlan.intents().subList(0, 7));
        assertThat(fixed.intents().getLast().coreActivity()).isEqualTo("교체 활동 i8");
    }
    @ParameterizedTest @ValueSource(strings = {"missing", "duplicate", "retained", "unknown-bucket"})
    void intentPatchCannotOmitRepeatExpandOrTouchRetainedIds(String mode) {
        rejectLastIntentUntilRepaired(); stages.patchMode = mode;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "REPAIR_INTENTS");
    }
    @Test void semanticPlannerIdsBecomeStableOpaqueHandlesBeforeAnyReviewOrRepair() {
        rejectLastIntentUntilRepaired(); stages.semanticIntentIds = true;
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates()).hasSize(8);
        assertThat(stages.originalPlan.intents()).allMatch(i -> i.id().startsWith("original_activity_"));
        var fixed = stages.seenPlans.getFirst();
        assertThat(stages.allocationPlans).allSatisfy(plan -> assertThat(plan.intents().stream().map(ActivityIntent::id))
                .containsExactly("i1", "i2", "i3", "i4", "i5", "i6", "i7", "i8"));
        assertThat(fixed.approvedIntents().getLast().coreActivity()).isEqualTo("교체 활동 i8");
        for (int i = 0; i < 7; i++) {
            assertThat(fixed.approvedIntents().get(i).coreActivity()).isEqualTo(stages.originalPlan.intents().get(i).coreActivity());
            assertThat(fixed.approvedIntents().get(i).fit()).isEqualTo(stages.originalPlan.intents().get(i).fit());
        }
        assertThat(fixed.specification().constraints()).isEqualTo(stages.originalPlan.constraints());
        assertThat(stages.intentRepairs).isEqualTo(1);
        assertThat(stages.repairs).isZero();
    }
    @Test void missingRejectionReasonNeverBecomesARepairRequest() {
        stages.allocationFunction = plan -> new AllocationReview(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS,
                plan.intents().stream().limit(7).map(ActivityIntent::id).toList(), List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
    }
    @Test void unfaithfulInterpretationWithTooFewActivitiesIsNotRepairable() {
        stages.allocationFunction = plan -> new AllocationReview(unfaithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS,
                List.of(), plan.intents().stream().map(i -> new IntentRejection(i.id(), "해석 불일치")).toList());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
    }
    @Test void aPreviouslyApprovedActivityCanFailTheFreshReallocation() {
        stages.intentCountOffset = 0;
        stages.allocationFunction = plan -> allocation(plan, plan.intents().stream()
                .filter(i -> !i.id().equals(stages.allocationCount == 1 ? "i8" : "i1")).map(ActivityIntent::id).toList());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "REPAIR_INTENTS", "ALLOCATE_REPAIRED");
    }
    @ParameterizedTest @ValueSource(strings = {"semantic", "malformed", "grounding"})
    void intentRepairConsumesTheSameBudgetAsDetailedCandidateRepair(String failure) {
        rejectLastIntentUntilRepaired();
        stages.malformedGeneration = failure.equals("malformed");
        stages.groundingRequired = failure.equals("grounding");
        if (failure.equals("semantic")) stages.reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS,
                Verdict.PASS, Verdict.FAIL, assessments(batch), feasibility(batch), List.of(new Finding("FILLER", List.of("c1"), "Invalid detail")));
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.intentRepairs).isEqualTo(1);
        assertThat(stages.repairs).isZero();
        assertThat(stages.calls).doesNotContain("REPAIR", "REVIEW_REPAIRED", "GROUND_REPAIRED");
    }
    @Test void expiredDeadlineCannotStartTheNewIntentRepairStage() {
        rejectLastIntentUntilRepaired(); stages.slowAllocation = true;
        assertThatThrownBy(() -> engine.generate(input(8), context())).isInstanceOfSatisfying(Failure.class,
                e -> assertThat(e.code()).isEqualTo(Failure.Code.PROVIDER_UNAVAILABLE));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
    }
    private void rejectLastIntentUntilRepaired() {
        stages.intentCountOffset = 0;
        stages.allocationFunction = plan -> allocation(plan, plan.intents().stream()
                .filter(i -> stages.intentRepairs > 0 || !i.id().equals("i8")).map(ActivityIntent::id).toList());
    }
    @Test void quotaIsDerivedFromApprovedActivitiesNotRejectedReadingVariants() {
        stages.readingVariants = true;
        stages.allocationFunction = plan -> allocation(plan, plan.intents().stream()
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
        stages.reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS, Verdict.PASS,
                count == 1 ? Verdict.FAIL : Verdict.PASS, assessments(batch), feasibility(batch),
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
        stages.omitConstraints = true;
        stages.reviewFunction = (batch, count) -> new Review(unfaithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), feasibility(batch), List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.originalPlan.constraints()).isEmpty();
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }
    @Test void separatelyAssessedPredicatesMayShareAVerbatimSourceExcerpt() {
        var constraints = List.of(new ConstraintSpec("home", "집에서 가능", "집에서 조용한", VerificationMode.SEMANTIC_ESTIMATE),
                new ConstraintSpec("quiet", "조용하게 가능", "집에서 조용한", VerificationMode.SEMANTIC_ESTIMATE));
        stages.planTransform = p -> new PlanProposal(p.decision(), p.unit(), p.hobby(), p.groundingRequired(), constraints, p.coverage(), p.softPreferences());
        stages.reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS,
                batch.candidates().stream().flatMap(c -> constraints.stream().map(condition -> new Assessment(c.id(), condition.id(), Verdict.PASS))).toList(), feasibility(batch), List.of());
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().plan().hardConstraints()).containsExactly(
                new HardConstraint("home", VerificationMode.SEMANTIC_ESTIMATE), new HardConstraint("quiet", VerificationMode.SEMANTIC_ESTIMATE));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL");
    }
    @Test void bundledInterpretationFindingStopsBeforeGenerationWithoutCandidateRepair() {
        stages.planTransform = p -> new PlanProposal(p.decision(), p.unit(), p.hobby(), p.groundingRequired(),
                List.of(new ConstraintSpec("home-quiet", "집에서 조용하게 가능", "집에서 조용한", VerificationMode.SEMANTIC_ESTIMATE)), p.coverage(), p.softPreferences());
        stages.allocationFunction = p -> new AllocationReview(new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(
                InterpretationField.CONSTRAINTS, "집에서 조용한", "장소와 소음 조건을 각각 검증해야 함"))),
                Verdict.PASS, Verdict.PASS, Verdict.PASS, p.intents().stream().map(ActivityIntent::id).toList(), List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE");
        assertThat(stages.repairs + stages.intentRepairs).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"allocation", "final"})
    void malformedOrContradictoryInterpretationEvidenceNeverAllowsRepair(String phase) {
        var finding = new InterpretationFinding(InterpretationField.CONSTRAINTS, "조용한", "해석 불일치");
        var reviews = List.of(
                new InterpretationReview(Verdict.PASS, List.of(finding)),
                new InterpretationReview(Verdict.FAIL, List.of()),
                new InterpretationReview(Verdict.UNKNOWN, List.of()),
                new InterpretationReview(null, List.of()),
                new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(null, "조용한", "해석 불일치"))),
                new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(InterpretationField.CONSTRAINTS, "없는 원문", "해석 불일치"))),
                new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(InterpretationField.CONSTRAINTS, "조용한", " "))));
        for (var review : reviews) {
            stages.calls.clear();
            stages.allocationFunction = plan -> new AllocationReview(phase.equals("allocation") ? review : faithful(),
                    Verdict.PASS, Verdict.PASS, Verdict.PASS, plan.intents().stream().map(ActivityIntent::id).toList(), List.of());
            stages.reviewFunction = (batch, count) -> new Review(review, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), feasibility(batch), List.of());
            qualityFailure(() -> engine.generate(input(8), context()));
            assertThat(stages.calls).doesNotContain("REPAIR", "REPAIR_INTENTS");
            if (phase.equals("allocation")) assertThat(stages.calls).doesNotContain("GENERATE");
        }
    }
    @Test void uncertainGroundingInterpretationRemainsTerminalRatherThanBecomingCandidateRepair() {
        var request = new GenerationInput("조용한 실내 장소, 오늘 영업 중인 곳", 8, "ko-KR", "Asia/Seoul");
        var interpretation = new InterpretationReview(Verdict.UNKNOWN, List.of(new InterpretationFinding(
                InterpretationField.GROUNDING_REQUIRED, "오늘 영업 중인 곳", "계획에 현재 영업 확인 요구가 없음")));
        stages.reviewFunction = (batch, count) -> new Review(interpretation, Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), feasibility(batch), List.of());
        qualityFailure(() -> engine.generate(request, context()));
        assertThat(stages.originalPlan.groundingRequired()).isFalse();
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }
    @Test void unknownHardConstraintRequiresRepairAndIndependentRecheck() {
        stages.reviewFunction = (batch, count) -> {
            var assessments = new ArrayList<>(assessments(batch));
            if (count == 1) assessments.set(0, new Assessment("c1", "quiet", Verdict.UNKNOWN));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments, feasibility(batch), List.of());
        };
        var result = engine.generate(input(8), context());
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
        assertThat(result.candidates().candidates().getFirst().name()).contains("수정");
    }
    @ParameterizedTest
    @CsvSource({"8, UNKNOWN, false", "8, FAIL, false", "16, UNKNOWN, false", "16, FAIL, false", "32, UNKNOWN, false", "32, FAIL, false",
            "8, UNKNOWN, true", "8, FAIL, true", "16, UNKNOWN, true", "16, FAIL, true", "32, UNKNOWN, true", "32, FAIL, true"})
    void candidateFeasibilityRequiresTargetedRepairDespiteAllAggregatePasses(int size, Verdict verdict, boolean noConstraints) {
        stages.omitConstraints = noConstraints;
        stages.useAlternative = true;
        stages.reviewFunction = (batch, count) -> {
            var evidence = new ArrayList<>(feasibility(batch));
            if (count == 1) evidence.set(size - 1, new FeasibilityAssessment("c" + size, verdict, "필수 관찰 환경에 접근할 수 있는지 확인되지 않음"));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS,
                    noConstraints ? List.of() : assessments(batch), evidence, List.of());
        };

        var result = engine.generate(input(size), context());

        assertThat(result.candidates().candidates()).hasSize(size);
        assertThat(result.candidates().candidates().getLast().name()).isEqualTo("대체 활동");
        assertThat(result.candidates().candidates().subList(0, size - 1).stream().map(Candidate::name))
                .containsExactlyElementsOf(IntStream.range(1, size).mapToObj(i -> "합성 취미 " + i).toList());
        assertThat(stages.lastReplacementIds).containsExactly("c" + size);
        assertThat(stages.lastFindings).containsExactly(new Finding("FEASIBILITY_UNVERIFIED", List.of("c" + size), "필수 관찰 환경에 접근할 수 있는지 확인되지 않음"));
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(strings = {"UNKNOWN", "FAIL"})
    void feasibilityFailureAfterRepairNeverExposesTheSetOrRepairsAgain(Verdict verdict) {
        stages.reviewFunction = (batch, count) -> {
            var evidence = new ArrayList<>(feasibility(batch));
            evidence.set(0, new FeasibilityAssessment("c1", verdict, "필수 접근 조건이 여전히 충족되지 않음"));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), evidence, List.of());
        };
        qualityFailure(() -> engine.generate(input(32), context()));
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }
    @ParameterizedTest @ValueSource(strings = {"empty", "missing", "duplicate", "extra", "unknown-id", "null-id", "blank-reason", "null-reason", "long-reason", "null-verdict"})
    void malformedFeasibilityEvidenceIsTerminalWithoutRepair(String mode) {
        stages.reviewFunction = (batch, count) -> {
            var evidence = new ArrayList<>(feasibility(batch));
            switch (mode) {
                case "empty" -> evidence.clear();
                case "missing" -> evidence.removeLast();
                case "duplicate" -> evidence.set(7, evidence.getFirst());
                case "extra" -> evidence.add(new FeasibilityAssessment("c9", Verdict.PASS, "불필요한 평가"));
                case "unknown-id" -> evidence.set(7, new FeasibilityAssessment("invented", Verdict.PASS, "잘못된 식별자"));
                case "null-id" -> evidence.set(7, new FeasibilityAssessment(null, Verdict.PASS, "식별자 누락"));
                case "blank-reason" -> evidence.set(7, new FeasibilityAssessment("c8", Verdict.PASS, " \n\t"));
                case "null-reason" -> evidence.set(7, new FeasibilityAssessment("c8", Verdict.PASS, null));
                case "long-reason" -> evidence.set(7, new FeasibilityAssessment("c8", Verdict.PASS, "가".repeat(301)));
                case "null-verdict" -> evidence.set(7, new FeasibilityAssessment("c8", null, "판정 누락"));
                default -> throw new AssertionError(mode);
            }
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), evidence, List.of());
        };
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.repairs + stages.intentRepairs).isZero();
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL");
    }
    @Test void priorIntentRepairLeavesNoBudgetForAnUnverifiedEssentialPrerequisite() {
        rejectLastIntentUntilRepaired();
        stages.reviewFunction = (batch, count) -> {
            var evidence = new ArrayList<>(feasibility(batch));
            evidence.set(0, new FeasibilityAssessment("c1", Verdict.UNKNOWN, "필수 환경 접근 가능 여부가 알려지지 않음"));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), evidence, List.of());
        };
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.intentRepairs).isEqualTo(1);
        assertThat(stages.repairs).isZero();
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "REPAIR_INTENTS", "ALLOCATE_REPAIRED", "GENERATE", "REVIEW_INITIAL");
    }
    @Test void semanticDuplicateRepairChangesOnlyIdentifiedCandidate() {
        stages.reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS,
                count == 1 ? Verdict.FAIL : Verdict.PASS, Verdict.PASS, assessments(batch), feasibility(batch),
                count == 1 ? List.of(new Finding("DUPLICATE_ACTIVITY", List.of("c2"), "Same core activity")) : List.of());
        engine.generate(input(8), context());
        assertThat(stages.lastReplacementIds).containsExactly("c2");
        assertThat(stages.calls).containsExactly("PLAN", "ALLOCATE", "GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }
    @Test void qualityFailureAfterOneRepairNeverLoopsOrShrinksSize() {
        stages.reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.FAIL,
                assessments(batch), feasibility(batch), List.of(new Finding("FILLER", List.of("c1"), "Forced filler")));
        qualityFailure(() -> engine.generate(input(32), context()));
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.seenPlans).allMatch(p -> p.input().size() == 32);
        assertThat(stages.seenPlans).allSatisfy(p -> assertThat(p).isSameAs(stages.seenPlans.getFirst()));
    }
    @Test void repairCannotModifyAnUnflaggedCandidate() {
        stages.modifyUnflagged = true;
        stages.reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.FAIL,
                assessments(batch), feasibility(batch), List.of(new Finding("FILLER", List.of("c1"), "Forced filler")));
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
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void groundingCannotBeReplacedWithSemanticSelfApproval(boolean entityAvailabilityRequired) {
        stages.groundingRequired = entityAvailabilityRequired;
        stages.constraintMode = entityAvailabilityRequired ? VerificationMode.SEMANTIC_ESTIMATE : VerificationMode.GROUNDED_FACT;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).contains("GROUND_INITIAL", "GROUND_REPAIRED");
        assertThat(stages.seenPlans).allSatisfy(p -> assertThat(p.gatePlan().hardConstraints())
                .containsExactly(new HardConstraint("quiet", stages.constraintMode)));
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
    private List<FeasibilityAssessment> feasibility(Batch batch) {
        return batch.candidates().stream().map(c -> new FeasibilityAssessment(c.id(), Verdict.PASS, "일반 준비물로 반복할 수 있는 합성 활동")).toList();
    }
    private AllocationReview allocation(PlanProposal plan, List<String> ids) {
        return new AllocationReview(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, ids,
                plan.intents().stream().filter(i -> !ids.contains(i.id())).map(i -> new IntentRejection(i.id(), "합성 부적합 활동")).toList());
    }
    private Batch batch(FixedPlan plan) { return new Batch(IntStream.rangeClosed(1, plan.input().size()).mapToObj(i -> {
        var intent = plan.approvedIntents().get(i - 1);
        return new Proposal("c" + i, intent.id(), "합성 취미 " + i, intent.bucketId(), List.of("합성"),
                stages.changeCore ? "승인받지 않은 활동" : intent.coreActivity(), "테스트 설명", "주 단위 연습", "준비 조건");
    }).toList()); }
    private Proposal change(Proposal c) { return new Proposal(c.id(), c.intentId(), c.name() + " 수정", c.bucketId(), c.tags(), c.coreActivity(), c.description(), c.repeatability(), c.requirements()); }
    private final class FakeStages implements EngineStages {
        final List<String> calls = new ArrayList<>(); final List<FixedPlan> seenPlans = new ArrayList<>();
        final List<PlanProposal> allocationPlans = new ArrayList<>();
        int intentCountOffset = 2, repairs, intentRepairs, allocationCount, reviewCount;
        boolean modifyUnflagged, malformedGeneration, groundingRequired, slowGeneration, slowAllocation, readingVariants, changeCore, useAlternative, omitConstraints, semanticIntentIds;
        PlanProposal originalPlan;
        String patchMode = "valid";
        String source = "조용한"; List<String> lastReplacementIds; List<Finding> lastFindings;
        VerificationMode constraintMode = VerificationMode.SEMANTIC_ESTIMATE;
        Function<PlanProposal, PlanProposal> planTransform = Function.identity();
        Function<PlanProposal, AllocationReview> allocationFunction = plan -> allocation(plan, plan.intents().stream().map(ActivityIntent::id).toList());
        BiFunction<Batch, Integer, Review> reviewFunction = (batch, count) -> new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), feasibility(batch), List.of());
        @Override public StageResult<PlanProposal> plan(GenerationInput input, List<CandidateEngine.Preference> history, Instant time, CallContext call) {
            var intents = IntStream.rangeClosed(1, input.size() + intentCountOffset)
                    .mapToObj(i -> new IntentSpec((semanticIntentIds ? "original_activity_" : "i") + i, "핵심 활동 " + i, "합성 적합성 설명")).toList();
            calls.add(call.stage()); originalPlan = new PlanProposal(Decision.READY, "취미", true, groundingRequired,
                    omitConstraints ? List.of() : List.of(new ConstraintSpec("quiet", "조용해야 한다", source, constraintMode)),
                    readingVariants ? List.of(new BucketSpec("reading", "독서", intents.subList(0, 3)), new BucketSpec("broad", "합성 테스트 활동", intents.subList(3, intents.size())))
                            : List.of(new BucketSpec("broad", "합성 테스트 활동", intents)), List.of());
            originalPlan = planTransform.apply(originalPlan);
            return new StageResult<>(originalPlan, "fake-plan");
        }
        @Override public StageResult<AllocationReview> allocate(GenerationInput input, Instant time, PlanProposal proposal, CallContext call) {
            allocationCount++;
            allocationPlans.add(proposal);
            if (slowAllocation) clock.time = clock.time.plusSeconds(300);
            calls.add(call.stage()); return new StageResult<>(allocationFunction.apply(proposal), "independent-allocation");
        }
        @Override public StageResult<IntentRepairs> repairIntents(GenerationInput input, Instant time, PlanProposal original,
                                                               List<IntentRejection> rejections, CallContext call) {
            calls.add(call.stage()); intentRepairs++;
            var patch = new ArrayList<>(rejections.stream().map(r -> new ActivityIntent(r.intentId(), original.coverage().getFirst().id(),
                    "교체 활동 " + r.intentId(), "합성 교체 적합성")).toList());
            switch (patchMode) {
                case "missing" -> patch.removeFirst();
                case "duplicate" -> patch.add(patch.getFirst());
                case "retained" -> patch.add(original.intents().getFirst());
                case "unknown-bucket" -> patch.set(0, new ActivityIntent(patch.getFirst().id(), "unknown", "교체", "적합"));
            }
            return new StageResult<>(new IntentRepairs(patch), "fake-intent-repair");
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
            calls.add(call.stage()); seenPlans.add(plan); repairs++; lastReplacementIds = ids; lastFindings = findings;
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
