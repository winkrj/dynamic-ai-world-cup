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
    private InterpretationReview interpretation(Verdict verdict, InterpretationField field, String excerpt) {
        return new InterpretationReview(verdict, List.of(new InterpretationFinding(field, excerpt, "조건 해석 불일치")));
    }
    private GenerationInput input(int size) { return new GenerationInput("집에서 조용한 취미", size, "ko-KR", "Asia/Seoul"); }
    private CandidateEngine.Context context() { return new CandidateEngine.Context("test-job", 1, clock.instant().plusSeconds(300), List.of()); }
    private void fails(Failure.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(Failure.class, e -> assertThat(e.code()).isEqualTo(code));
    }
    private void qualityFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        fails(Failure.Code.QUALITY_GATE_FAILED, work);
    }

    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void exposesExactValidatedSizeWithOneGenerationAndIndependentReview(int size) {
        var result = engine.generate(input(size), context());
        assertThat(result.candidates().candidates()).hasSize(size);
        assertThat(result.candidates().candidates()).extracting(Candidate::id).containsExactlyElementsOf(StagedCandidateEngine.allIds(size));
        assertThat(result.publicTitle()).isEqualTo(size + "강 선택 월드컵");
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
        assertThat(result.providerVersion()).isEqualTo("fake-generation");
        assertThat(result.validatorVersion()).isEqualTo("independent-fake-review-1");
        var certificate = result.certificate();
        assertThat(certificate).isNotNull();
        assertThat(certificate.plan()).isEqualTo(result.candidates().plan());
        assertThat(certificate.candidates()).isEqualTo(result.candidates().candidates());
        assertThat(certificate.richCandidates()).isEqualTo(stages.originalBatch.candidates());
        assertThat(certificate.finalReview().findings()).isEmpty();
        assertThat(certificate.finalReview().feasibility()).hasSize(size);
        assertThat(certificate.finalReview().assessments()).isEqualTo(certificate.evidence().assessments());
        assertThat(certificate.evidence().reviewerVersion()).isEqualTo(result.validatorVersion());
        String serialized = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(certificate);
        assertThat(serialized).doesNotContain("\"sourceText\"", "\"prompt\"", "\"recentDirectChoices\"",
                "\"allocationInterpretation\"", "\"allocationComparable\"", "\"allocationNoSemanticDuplicates\"",
                "\"allocationFeasible\"", "\"intentId\"", input(size).prompt());
    }

    @ParameterizedTest @ValueSource(strings = {"empty-coverage", "zero-quota", "excess-quota", "wrong-sum", "duplicate-bucket",
            "invalid-bucket", "blank-bucket", "blank-unit", "null-decision", "invented-source", "reserved-constraint",
            "duplicate-constraint", "null-mode", "blank-preference"})
    void malformedPlanCannotBeRepairedIntoWeakerConditions(String defect) {
        stages.planTransform = p -> {
            var coverage = p.coverage();
            var constraints = p.constraints();
            String unit = p.unit();
            var decision = p.decision();
            var preferences = p.softPreferences();
            switch (defect) {
                case "empty-coverage" -> coverage = List.of();
                case "zero-quota" -> coverage = List.of(new CoverageSpec("broad", "활동", 0));
                case "excess-quota" -> coverage = List.of(new CoverageSpec("broad", "활동", 9));
                case "wrong-sum" -> coverage = List.of(new CoverageSpec("broad", "활동", 7));
                case "duplicate-bucket" -> coverage = List.of(new CoverageSpec("broad", "활동", 4), new CoverageSpec("broad", "활동", 4));
                case "invalid-bucket" -> coverage = List.of(new CoverageSpec("Unknown ID", "활동", 8));
                case "blank-bucket" -> coverage = List.of(new CoverageSpec("broad", " ", 8));
                case "blank-unit" -> unit = " ";
                case "null-decision" -> decision = null;
                case "invented-source" -> constraints = List.of(new ConstraintSpec("quiet", "조용함", "말하지 않은 조건", VerificationMode.SEMANTIC_ESTIMATE));
                case "reserved-constraint" -> constraints = List.of(new ConstraintSpec("availability", "조용함", "조용한", VerificationMode.SEMANTIC_ESTIMATE));
                case "duplicate-constraint" -> constraints = List.of(p.constraints().getFirst(), p.constraints().getFirst());
                case "null-mode" -> constraints = List.of(new ConstraintSpec("quiet", "조용함", "조용한", null));
                case "blank-preference" -> preferences = List.of(" ");
                default -> throw new AssertionError(defect);
            }
            return new RequestPlan(decision, unit, p.hobby(), p.groundingRequired(), constraints, coverage, preferences);
        };
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE");
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"CLARIFICATION_REQUIRED", "UNSUPPORTED_REQUEST"})
    void nonReadyDecisionStopsBeforeReviewAndRepair(String decision) {
        stages.planTransform = p -> new RequestPlan(Decision.valueOf(decision), "", false, false, List.of(), List.of(), List.of());
        stages.batchTransform = ignored -> new Batch(List.of());
        fails(Failure.Code.valueOf(decision), () -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE");
    }

    @ParameterizedTest @ValueSource(strings = {"malformed", "null-proposal", "null-plan"})
    void unreadableCombinedOutputCannotBeRepairedWithoutATrustedFixedPlan(String defect) {
        stages.generationDefect = defect;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE");
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN"})
    void interpretationFailureCannotBeRepairedOrRewrittenIntoPass(Verdict verdict) {
        stages.reviewFunction = (batch, count) -> review(batch, interpretation(verdict, InterpretationField.UNIT, "취미"),
                Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN"})
    void interpretationMustPassAgainAfterCandidateRepair(Verdict verdict) {
        stages.reviewFunction = (batch, count) -> count == 1
                ? review(batch, faithful(), Verdict.PASS, Verdict.FAIL, Verdict.PASS,
                        List.of(new Finding("DUPLICATE", List.of("c1"), "핵심 활동 중복")))
                : review(batch, interpretation(verdict, InterpretationField.UNIT, "취미"),
                        Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());
        qualityFailure(() -> engine.generate(input(16), context()));
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }

    @Test void omittedConstraintIsTerminalEvenWhenEveryCandidatePasses() {
        stages.omitConstraints = true;
        stages.reviewFunction = (batch, count) -> review(batch, interpretation(Verdict.FAIL, InterpretationField.CONSTRAINTS, "조용한"),
                Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.originalPlan.constraints()).isEmpty();
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN"})
    void exclusionMisclassifiedAsPreferenceCannotBeRescuedByCandidatePasses(Verdict verdict) {
        var request = new GenerationInput("혼자 하는 걸 좋아하고 운동은 싫어. 조용한 취미를 찾아줘", 8, "ko-KR", "Asia/Seoul");
        stages.planTransform = p -> new RequestPlan(p.decision(), "반복 가능한 취미 활동", true, false,
                p.constraints(), p.coverage(), List.of("혼자 하는 활동 선호", "운동은 싫어함"));
        stages.reviewFunction = (batch, count) -> review(batch, interpretation(verdict, InterpretationField.CONSTRAINTS, "운동은 싫어"),
                Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());
        qualityFailure(() -> engine.generate(request, context()));
        assertThat(stages.originalPlan.constraints()).extracting(ConstraintSpec::id).containsExactly("quiet");
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"null", "null-verdict", "pass-with-finding", "fail-without-finding",
            "unknown-without-finding", "null-field", "invented-source", "blank-detail", "too-many"})
    void malformedInterpretationEvidenceIsTerminal(String defect) {
        var finding = new InterpretationFinding(InterpretationField.CONSTRAINTS, "조용한", "해석 불일치");
        InterpretationReview malformed = switch (defect) {
            case "null" -> null;
            case "null-verdict" -> new InterpretationReview(null, List.of());
            case "pass-with-finding" -> new InterpretationReview(Verdict.PASS, List.of(finding));
            case "fail-without-finding" -> new InterpretationReview(Verdict.FAIL, List.of());
            case "unknown-without-finding" -> new InterpretationReview(Verdict.UNKNOWN, List.of());
            case "null-field" -> new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(null, "조용한", "불일치")));
            case "invented-source" -> new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(InterpretationField.CONSTRAINTS, "없는 원문", "불일치")));
            case "blank-detail" -> new InterpretationReview(Verdict.FAIL, List.of(new InterpretationFinding(InterpretationField.CONSTRAINTS, "조용한", " ")));
            case "too-many" -> new InterpretationReview(Verdict.FAIL, java.util.Collections.nCopies(13, finding));
            default -> throw new AssertionError(defect);
        };
        stages.reviewFunction = (batch, count) -> review(batch, malformed, Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }

    @Test void separatePredicatesCanShareVerbatimSourceWithoutInventingAnotherCondition() {
        var constraints = List.of(new ConstraintSpec("home", "집에서 가능", "집에서 조용한", VerificationMode.SEMANTIC_ESTIMATE),
                new ConstraintSpec("quiet", "조용하게 가능", "집에서 조용한", VerificationMode.SEMANTIC_ESTIMATE));
        stages.planTransform = p -> new RequestPlan(p.decision(), p.unit(), p.hobby(), p.groundingRequired(), constraints, p.coverage(), p.softPreferences());
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().plan().hardConstraints()).containsExactly(
                new HardConstraint("home", VerificationMode.SEMANTIC_ESTIMATE), new HardConstraint("quiet", VerificationMode.SEMANTIC_ESTIMATE));
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
    }

    @Test void neutralUnitAndPreferencesStayFixedWhileFlaggedCoreActivityCanChange() {
        stages.planTransform = p -> new RequestPlan(p.decision(), "반복 가능한 취미 활동", true, false,
                p.constraints(), p.coverage(), List.of("혼자 하는 활동 선호"));
        stages.reviewFunction = (batch, count) -> review(batch, faithful(), Verdict.PASS,
                count == 1 ? Verdict.FAIL : Verdict.PASS, Verdict.PASS,
                count == 1 ? List.of(new Finding("DUPLICATE_ACTIVITY", List.of("c2"), "다른 후보와 핵심 활동 중복")) : List.of());
        var result = engine.generate(new GenerationInput("혼자 하는 걸 좋아하고 조용한 취미를 원해", 8, "ko-KR", "Asia/Seoul"), context());
        assertThat(stages.lastReplacementIds).containsExactly("c2");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
        assertThat(result.certificate().richCandidates().get(1).coreActivity()).isNotEqualTo(stages.originalBatch.candidates().get(1).coreActivity());
        for (int i = 0; i < 8; i++) if (i != 1) {
            assertThat(result.certificate().richCandidates().get(i)).isEqualTo(stages.originalBatch.candidates().get(i));
        }
        assertThat(stages.seenPlans).allSatisfy(p -> {
            assertThat(p).isSameAs(stages.seenPlans.getFirst());
            assertThat(p.specification()).isSameAs(stages.originalPlan);
            assertThat(p.specification().unit()).isEqualTo("반복 가능한 취미 활동");
            assertThat(p.specification().softPreferences()).containsExactly("혼자 하는 활동 선호");
            assertThat(p.gatePlan().hardConstraints()).containsExactly(new HardConstraint("quiet", VerificationMode.SEMANTIC_ESTIMATE));
        });
        assertThat(result.validatorVersion()).isEqualTo("independent-fake-review-2");
        assertThat(result.certificate().finalReview()).isEqualTo(stages.lastReview);
    }

    @Test void repairCannotModifyAnUnflaggedCandidateEvenWhenTheFinalReviewerWouldApprove() {
        stages.modifyUnflagged = true;
        stages.reviewFunction = (batch, count) -> review(batch, faithful(), Verdict.PASS, Verdict.PASS,
                count == 1 ? Verdict.FAIL : Verdict.PASS,
                count == 1 ? List.of(new Finding("FILLER", List.of("c1"), "부적합 후보")) : List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL", "REPAIR");
        assertThat(stages.reviewCount).isEqualTo(1);
    }

    @Test void aNewDefectFoundDuringFullRereviewCannotReceiveASecondRepair() {
        stages.reviewFunction = (batch, count) -> review(batch, faithful(), Verdict.PASS, Verdict.FAIL, Verdict.PASS,
                List.of(new Finding("DUPLICATE_ACTIVITY", List.of(count == 1 ? "c1" : "c8"), "전체 세트에서 다시 발견된 중복")));
        qualityFailure(() -> engine.generate(input(32), context()));
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewedBatches).allSatisfy(b -> assertThat(b.candidates()).hasSize(32));
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN"})
    void serverNeverReinterpretsAQualityFailureAsSubjectiveApproval(Verdict verdict) {
        // A synthetic inconsistent reviewer still cannot be bypassed by a server keyword allowlist.
        stages.reviewFunction = (batch, count) -> review(batch, faithful(), Verdict.PASS, Verdict.PASS, verdict,
                List.of(new Finding("APPEAL", List.of("c1"), "평범해서 덜 매력적임")));
        qualityFailure(() -> engine.generate(input(32), context()));
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
        assertThat(stages.seenPlans).allMatch(p -> p.input().size() == 32);
    }

    @ParameterizedTest
    @CsvSource({"8,UNKNOWN,false", "8,FAIL,false", "16,UNKNOWN,false", "16,FAIL,false", "32,UNKNOWN,false", "32,FAIL,false",
            "8,UNKNOWN,true", "8,FAIL,true", "16,UNKNOWN,true", "16,FAIL,true", "32,UNKNOWN,true", "32,FAIL,true"})
    void everyCandidateNeedsFeasibilityEvenWithoutHardConstraints(int size, Verdict verdict, boolean noConstraints) {
        stages.omitConstraints = noConstraints;
        stages.reviewFunction = (batch, count) -> {
            var evidence = new ArrayList<>(feasibility(batch));
            if (count == 1) evidence.set(size - 1, new FeasibilityAssessment("c" + size, verdict, "필수 관찰 환경 미확인"));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), evidence, List.of());
        };
        var result = engine.generate(input(size), context());
        assertThat(result.candidates().candidates()).hasSize(size);
        assertThat(stages.lastReplacementIds).containsExactly("c" + size);
        assertThat(stages.lastFindings).containsExactly(new Finding("FEASIBILITY_UNVERIFIED", List.of("c" + size), "필수 관찰 환경 미확인"));
        assertThat(result.certificate().richCandidates().subList(0, size - 1)).isEqualTo(stages.originalBatch.candidates().subList(0, size - 1));
        assertThat(result.certificate().richCandidates().getLast().coreActivity()).isNotEqualTo(stages.originalBatch.candidates().getLast().coreActivity());
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }

    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN"})
    void unresolvedFeasibilityAfterRepairNeverExposesASet(Verdict verdict) {
        stages.reviewFunction = (batch, count) -> {
            var evidence = new ArrayList<>(feasibility(batch));
            evidence.set(0, new FeasibilityAssessment("c1", verdict, "필수 접근 조건 미확인"));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, assessments(batch), evidence, List.of());
        };
        qualityFailure(() -> engine.generate(input(32), context()));
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "missing", "duplicate", "extra", "unknown-id", "null-id",
            "blank-reason", "null-reason", "long-reason", "null-verdict"})
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
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"FAIL", "UNKNOWN", "missing"})
    void passingFeasibilityCannotOverrideAnUnverifiedHardConstraint(String mode) {
        stages.reviewFunction = (batch, count) -> {
            var conditions = new ArrayList<>(assessments(batch));
            if (mode.equals("missing")) conditions.removeFirst();
            else conditions.set(0, new Assessment("c1", "quiet", Verdict.valueOf(mode)));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, conditions, feasibility(batch), List.of());
        };
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED");
    }

    @Test void repairedHardConstraintEvidenceIsFreshAndStoredInTheCertificate() {
        stages.reviewFunction = (batch, count) -> {
            var conditions = new ArrayList<>(assessments(batch));
            if (count == 1) conditions.set(0, new Assessment("c1", "quiet", Verdict.UNKNOWN));
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, conditions, feasibility(batch), List.of());
        };
        var result = engine.generate(input(8), context());
        assertThat(stages.lastReplacementIds).containsExactly("c1");
        assertThat(result.certificate().evidence().assessments()).allMatch(a -> a.verdict() == Verdict.PASS);
        assertThat(result.certificate().finalReview().assessments()).isEqualTo(result.certificate().evidence().assessments());
        assertThat(result.certificate().richCandidates().getFirst().coreActivity()).startsWith("교체 핵심");
    }

    @ParameterizedTest @ValueSource(strings = {"duplicate", "unknown-id", "unknown-constraint", "null-verdict"})
    void invalidHardEvidenceCannotApproveACandidateEvenWithAllAggregatePasses(String mode) {
        stages.reviewFunction = (batch, count) -> {
            var conditions = new ArrayList<>(assessments(batch));
            switch (mode) {
                case "duplicate" -> conditions.add(conditions.getFirst());
                case "unknown-id" -> conditions.set(0, new Assessment("invented", "quiet", Verdict.PASS));
                case "unknown-constraint" -> conditions.set(0, new Assessment("c1", "invented", Verdict.PASS));
                case "null-verdict" -> conditions.set(0, new Assessment("c1", "quiet", null));
                default -> throw new AssertionError(mode);
            }
            return new Review(faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, conditions, feasibility(batch), List.of());
        };
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.reviewCount).isEqualTo(2);
    }

    @ParameterizedTest @ValueSource(strings = {"empty", "missing", "duplicate-id", "unknown-id", "unknown-bucket", "blank-core", "duplicate-name"})
    void invalidCardsGetOneRepairUnderTheSameTrustedPlanBeforeIndependentReview(String mode) {
        stages.batchTransform = original -> {
            var cards = new ArrayList<>(original.candidates());
            var last = cards.getLast();
            switch (mode) {
                case "empty" -> cards.clear();
                case "missing" -> cards.removeLast();
                case "duplicate-id" -> cards.set(7, cards.getFirst());
                case "unknown-id" -> cards.set(7, new Proposal("invented", last.name(), last.bucketId(), last.tags(), last.coreActivity(), last.description(), last.repeatability(), last.requirements()));
                case "unknown-bucket" -> cards.set(7, new Proposal(last.id(), last.name(), "missing", last.tags(), last.coreActivity(), last.description(), last.repeatability(), last.requirements()));
                case "blank-core" -> cards.set(7, new Proposal(last.id(), last.name(), last.bucketId(), last.tags(), " ", last.description(), last.repeatability(), last.requirements()));
                case "duplicate-name" -> cards.set(7, new Proposal(last.id(), cards.getFirst().name(), last.bucketId(), last.tags(), last.coreActivity(), last.description(), last.repeatability(), last.requirements()));
                default -> throw new AssertionError(mode);
            }
            return new Batch(cards);
        };
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates()).hasSize(8);
        assertThat(stages.calls).containsExactly("GENERATE", "REPAIR", "REVIEW_REPAIRED");
        assertThat(stages.repairs).isEqualTo(1);
        assertThat(stages.seenPlans).allSatisfy(p -> assertThat(p).isSameAs(stages.seenPlans.getFirst()));
    }

    @Test void malformedRepairedCardsFailWithoutAnotherRepairOrReview() {
        stages.batchTransform = b -> new Batch(List.of());
        stages.repairTransform = b -> new Batch(List.of());
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE", "REPAIR");
        assertThat(stages.repairs).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void missingFactualEvidenceCannotBeReplacedBySemanticApproval(boolean availability) {
        stages.groundingRequired = availability;
        stages.constraintMode = availability ? VerificationMode.SEMANTIC_ESTIMATE : VerificationMode.GROUNDED_FACT;
        qualityFailure(() -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactly("GENERATE", "GROUND_INITIAL", "REVIEW_INITIAL", "REPAIR", "GROUND_REPAIRED", "REVIEW_REPAIRED");
        assertThat(stages.repairs).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void currentBoundFactsCanSupportAvailabilityOrGroundedConditions(boolean availability) {
        stages.groundingRequired = availability;
        stages.constraintMode = availability ? VerificationMode.SEMANTIC_ESTIMATE : VerificationMode.GROUNDED_FACT;
        stages.validGrounding = true;
        var result = engine.generate(input(8), context());
        assertThat(stages.calls).containsExactly("GENERATE", "GROUND_INITIAL", "REVIEW_INITIAL");
        assertThat(result.certificate().evidence().facts()).hasSize(8);
        assertThat(result.certificate().evidence().facts()).extracting(GroundedFact::claimKey)
                .containsOnly(availability ? "availability" : "quiet");
        assertThat(stages.reviewedGrounding.getFirst().facts()).isEqualTo(result.certificate().evidence().facts());
    }

    @Test void missingGroundingClassificationIsAnInterpretationFailureNotCandidateRepair() {
        var request = new GenerationInput("조용한 실내 장소, 오늘 영업 중인 곳", 8, "ko-KR", "Asia/Seoul");
        stages.reviewFunction = (batch, count) -> review(batch, interpretation(Verdict.UNKNOWN, InterpretationField.GROUNDING_REQUIRED, "오늘 영업 중인 곳"),
                Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());
        qualityFailure(() -> engine.generate(request, context()));
        assertThat(stages.originalPlan.groundingRequired()).isFalse();
        assertThat(stages.calls).containsExactly("GENERATE", "REVIEW_INITIAL");
    }

    @ParameterizedTest @ValueSource(strings = {"generation", "review"})
    void elapsedDeadlineStopsBeforeTheNextStageOrSuccessfulExposure(String stage) {
        stages.slowGeneration = stage.equals("generation");
        stages.slowReview = stage.equals("review");
        fails(Failure.Code.PROVIDER_UNAVAILABLE, () -> engine.generate(input(8), context()));
        assertThat(stages.calls).containsExactlyElementsOf(stage.equals("generation") ? List.of("GENERATE") : List.of("GENERATE", "REVIEW_INITIAL"));
        assertThat(stages.repairs).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"REVIEW_INITIAL", "REPAIR", "REVIEW_REPAIRED"})
    void repairPathAlsoHonorsDeadlineBeforeNextCallOrPublication(String slowStage) {
        stages.reviewFunction = (batch, count) -> {
            if (slowStage.equals(count == 1 ? "REVIEW_INITIAL" : "REVIEW_REPAIRED"))
                clock.time = clock.time.plusSeconds(300);
            return review(batch, faithful(), Verdict.PASS, count == 1 ? Verdict.FAIL : Verdict.PASS, Verdict.PASS,
                    count == 1 ? List.of(new Finding("DUPLICATE", List.of("c1"), "핵심 활동 중복")) : List.of());
        };
        stages.repairTransform = batch -> {
            if (slowStage.equals("REPAIR")) clock.time = clock.time.plusSeconds(300);
            return batch;
        };
        fails(Failure.Code.PROVIDER_UNAVAILABLE, () -> engine.generate(input(8), context()));
        assertThat(stages.calls.getLast()).isEqualTo(slowStage);
        assertThat(stages.repairs).isEqualTo(slowStage.equals("REVIEW_INITIAL") ? 0 : 1);
    }

    @Test void exhaustedDeadlineCannotStartAnyStage() {
        var expired = new CandidateEngine.Context("test-job", 1, clock.instant().plusSeconds(1), List.of());
        fails(Failure.Code.PROVIDER_UNAVAILABLE, () -> engine.generate(input(8), expired));
        assertThat(stages.calls).isEmpty();
    }

    @Test void interruptedThreadCannotStartAnyStage() {
        Thread.currentThread().interrupt();
        try { fails(Failure.Code.PROVIDER_UNAVAILABLE, () -> engine.generate(input(8), context())); }
        finally { Thread.interrupted(); }
        assertThat(stages.calls).isEmpty();
    }

    @Test void onlyFiftyDirectHistoryItemsReachGenerationAndNoHistoryEntersTheCertificate() {
        var history = IntStream.range(0, 70).mapToObj(i -> new CandidateEngine.Preference("취미", "private-choice-" + i, "rejected-" + i, clock.instant())).toList();
        var result = engine.generate(input(8), new CandidateEngine.Context("test-job", 1, clock.instant().plusSeconds(30), history));
        assertThat(stages.seenHistory).containsExactlyElementsOf(history.subList(0, 50));
        assertThat(stages.callContexts).allSatisfy(c -> {
            assertThat(c.jobId()).isEqualTo("test-job");
            assertThat(c.attempt()).isEqualTo(1);
            assertThat(c.deadline()).isEqualTo(clock.instant().plusSeconds(28));
        });
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result.certificate()))
                .doesNotContain("private-choice", "rejected-", "sourceText", "prompt");
    }

    private List<Assessment> assessments(Batch batch) {
        return batch.candidates().stream().flatMap(c -> stages.originalPlan.constraints().stream()
                .map(condition -> new Assessment(c.id(), condition.id(), Verdict.PASS))).toList();
    }
    private List<FeasibilityAssessment> feasibility(Batch batch) {
        return batch.candidates().stream().map(c -> new FeasibilityAssessment(c.id(), Verdict.PASS, "일반 준비물로 반복 가능한 합성 활동")).toList();
    }
    private Review review(Batch batch, InterpretationReview interpretation, Verdict comparable, Verdict distinct, Verdict quality, List<Finding> findings) {
        return new Review(interpretation, comparable, distinct, quality, assessments(batch), feasibility(batch), findings);
    }
    private Batch batch(int size) {
        return new Batch(IntStream.rangeClosed(1, size).mapToObj(i -> new Proposal("c" + i, "합성 취미 " + i, "broad", List.of("합성"),
                "핵심 활동 " + i, "테스트 설명", "주 단위 연습", "일반 준비 조건")).toList());
    }
    private Proposal replace(Proposal c) {
        return new Proposal(c.id(), "교체 취미 " + c.id(), c.bucketId(), c.tags(), "교체 핵심 활동 " + c.id(),
                "새 활동 설명", "지속적인 새 활동 연습", "명시 조건에 맞는 준비물");
    }
    private final class FakeStages implements EngineStages {
        final List<String> calls = new ArrayList<>();
        final List<FixedPlan> seenPlans = new ArrayList<>();
        final List<Batch> reviewedBatches = new ArrayList<>();
        final List<Grounding> reviewedGrounding = new ArrayList<>();
        final List<CallContext> callContexts = new ArrayList<>();
        int repairs, reviewCount;
        boolean modifyUnflagged, groundingRequired, validGrounding, slowGeneration, slowReview, omitConstraints;
        String generationDefect = "";
        RequestPlan originalPlan;
        Batch originalBatch;
        Review lastReview;
        List<CandidateEngine.Preference> seenHistory;
        List<String> lastReplacementIds;
        List<Finding> lastFindings;
        VerificationMode constraintMode = VerificationMode.SEMANTIC_ESTIMATE;
        Function<RequestPlan, RequestPlan> planTransform = Function.identity();
        Function<Batch, Batch> batchTransform = Function.identity();
        Function<Batch, Batch> repairTransform = Function.identity();
        BiFunction<Batch, Integer, Review> reviewFunction = (b, count) -> StagedCandidateEngineTest.this.review(b, faithful(), Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of());

        private void called(CallContext call) { calls.add(call.stage()); callContexts.add(call); }
        @Override public StageResult<GenerationProposal> generate(GenerationInput input, List<CandidateEngine.Preference> history, Instant time, CallContext call) {
            called(call); seenHistory = history;
            if (slowGeneration) clock.time = clock.time.plusSeconds(300);
            if (generationDefect.equals("malformed")) throw new InvalidModelOutput();
            if (generationDefect.equals("null-proposal")) return new StageResult<>(null, "fake-generation");
            originalPlan = planTransform.apply(new RequestPlan(Decision.READY, "취미", true, groundingRequired,
                    omitConstraints ? List.of() : List.of(new ConstraintSpec("quiet", "조용해야 한다", "조용한", constraintMode)),
                    List.of(new CoverageSpec("broad", "합성 활동", input.size())), List.of()));
            originalBatch = batchTransform.apply(batch(input.size()));
            return new StageResult<>(new GenerationProposal(generationDefect.equals("null-plan") ? null : originalPlan, originalBatch.candidates()), "fake-generation");
        }
        @Override public Grounding ground(FixedPlan plan, Batch batch, CallContext call) {
            called(call); seenPlans.add(plan);
            if (!validGrounding) return Grounding.empty();
            var claims = new ArrayList<String>();
            if (plan.specification().groundingRequired()) claims.add("availability");
            plan.specification().constraints().stream().filter(c -> c.mode() == VerificationMode.GROUNDED_FACT).forEach(c -> claims.add(c.id()));
            return new Grounding(batch.candidates().stream().flatMap(c -> claims.stream().map(claim ->
                    new GroundedFact(c.id(), claim, Verdict.PASS, "https://official.example/" + c.id(), "합성 근거",
                            clock.instant(), clock.instant().plusSeconds(3600)))).toList());
        }
        @Override public StageResult<Review> review(FixedPlan plan, Batch batch, Grounding grounding, CallContext call) {
            called(call); seenPlans.add(plan); reviewedBatches.add(batch); reviewedGrounding.add(grounding);
            lastReview = reviewFunction.apply(batch, ++reviewCount);
            if (slowReview) clock.time = clock.time.plusSeconds(300);
            return new StageResult<>(lastReview, "independent-fake-review-" + reviewCount);
        }
        @Override public StageResult<Batch> repair(FixedPlan plan, Batch original, List<String> ids, List<Finding> findings, CallContext call) {
            called(call); seenPlans.add(plan); repairs++; lastReplacementIds = ids; lastFindings = findings;
            var source = ids.size() == plan.input().size() ? batch(plan.input().size()) : original;
            var repaired = new Batch(source.candidates().stream().map(c -> ids.contains(c.id()) || modifyUnflagged ? replace(c) : c).toList());
            return new StageResult<>(repairTransform.apply(repaired), "fake-repair");
        }
    }
    private static final class MovingClock extends Clock {
        Instant time = Instant.parse("2026-09-15T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}
