package dev.worldcup.infrastructure.ai;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.generation.engine.EngineModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.infrastructure.JsonCodec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class RecordedAllocationTest {
    private final JsonCodec json = new JsonCodec(new JsonMapper());
    @TempDir Path temporary;
    private RecordedAllocation recorded() {
        return new RecordedAllocation(new GenerationInput("운동은 싫어. 취미를 찾고 있어", 32, "ko-KR", "Asia/Seoul"),
                Instant.parse("2026-09-18T07:33:00Z"), new PlanProposal(Decision.READY, "반복 가능한 취미", true, false,
                List.of(), List.of(new BucketSpec("b1", "경험", List.of(new IntentSpec("i1", "독서", "혼자 할 수 있음")))), List.of("운동을 싫어함")));
    }
    private Map<String, Object> exchange(String stage, int status, Object tools) {
        return Map.of("context", Map.of("stage", stage), "status", status,
                "requestBody", json.write(Map.of("model", "gpt-5.6-terra", "tools", tools, "input", json.write(recorded()))));
    }
    private AllocationReview review(Verdict verdict, InterpretationField field, String source, String detail) {
        return new AllocationReview(new InterpretationReview(verdict, List.of(new InterpretationFinding(field, source, detail))),
                Verdict.PASS, Verdict.PASS, Verdict.PASS, List.of("i1"), List.of());
    }
    @Test void unchangedRecordedRequestTimeAndProposalAreSentToAllocationExactlyOnce() {
        var parsed = RecordedAllocation.read(json.write(List.of(exchange("PLAN", 200, List.of()),
                exchange("ALLOCATE", 200, List.of()), exchange("ALLOCATE_REPAIRED", 200, List.of()))), json);
        assertThat(parsed).isEqualTo(recorded());
        var stages = mock(OpenAiCandidateStages.class);
        var call = new CallContext("diagnostic", 1, "ALLOCATE_DIAGNOSTIC", Instant.now().plusSeconds(90));
        var expected = new StageResult<>(review(Verdict.FAIL, InterpretationField.CONSTRAINTS, "운동은 싫어", "제외 조건을 선호로 잘못 분류함"), "stub");
        when(stages.allocate(parsed.request(), parsed.referenceTime(), parsed.proposal(), call)).thenReturn(expected);
        assertThat(parsed.reviewOnce(stages, call)).isSameAs(expected);
        verify(stages).allocate(parsed.request(), parsed.referenceTime(), parsed.proposal(), call);
        verifyNoMoreInteractions(stages);
        assertThat(parsed.proposal().constraints()).isEmpty();
        assertThat(parsed.proposal().softPreferences()).containsExactly("운동을 싫어함");
    }
    @Test void missingDuplicateFailedOrSearchEnabledAllocationCannotBeReplayed() {
        for (var rows : List.of(List.of(exchange("PLAN", 200, List.of())),
                List.of(exchange("ALLOCATE", 200, List.of()), exchange("ALLOCATE", 200, List.of())),
                List.of(exchange("ALLOCATE", 500, List.of())),
                List.of(exchange("ALLOCATE", 200, List.of(Map.of("type", "web_search")))))) {
            assertThatThrownBy(() -> RecordedAllocation.read(json.write(rows), json)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> RecordedAllocation.read("{}", json)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void failWithAnOriginalExclusionFindingIsReportedButCandidatePassesDoNotDecideIt() {
        assertThat(recorded().reportsExerciseExclusionMismatch(review(Verdict.FAIL, InterpretationField.CONSTRAINTS,
                "운동은 싫어", "제외 조건을 선호로 잘못 분류함"))).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"PASS", "UNKNOWN"})
    void otherInterpretationVerdictsAreNotDetection(String verdict) {
        assertThat(recorded().reportsExerciseExclusionMismatch(review(Verdict.valueOf(verdict), InterpretationField.CONSTRAINTS,
                "운동은 싫어", "제외 조건"))).isFalse();
    }
    @Test void unrelatedFieldInventedExcerptOrEmptyReasonCannotBeCountedAsDetection() {
        for (var review : List.of(review(Verdict.FAIL, InterpretationField.UNIT, "운동은 싫어", "단위 문제"),
                review(Verdict.FAIL, InterpretationField.CONSTRAINTS, "운동을 꼭 제외해", "조건 문제"),
                review(Verdict.FAIL, InterpretationField.CONSTRAINTS, "취미", "조건 문제"),
                review(Verdict.FAIL, InterpretationField.CONSTRAINTS, "운동은 싫어", " "))) {
            assertThat(recorded().reportsExerciseExclusionMismatch(review)).isFalse();
        }
    }
    @Test void cumulativeCostIncludesUnknownReservationsAndHistoricalComparisonCost() throws Exception {
        Path first = Files.createDirectory(temporary.resolve("first"));
        Path second = Files.createDirectory(temporary.resolve("second"));
        Files.writeString(first.resolve("ledger.json"), "[{\"id\":\"1\",\"accounted_usd\":0.1,\"state\":\"COMPLETED\"}]");
        Files.writeString(second.resolve("ledger.json"), "[{\"id\":\"2\",\"accounted_usd\":0.5,\"state\":\"FAILED_UNKNOWN_COST\"}]");
        assertThat(RecordedAllocation.accountedTotal(temporary, json)).isEqualByComparingTo("0.6725468");
    }
    @Test void ledgerReplacementIsCompleteAndFailedWritingPreservesThePriorReservation() throws Exception {
        Path file = temporary.resolve("ledger.json");
        String reserved = "[{\"id\":\"1\",\"accounted_usd\":0.5}]";
        RecordedAllocation.writeLedger(file, reserved);
        // Exercise the failure path before the atomic move; the existing ledger is never opened for writing.
        assertThatThrownBy(() -> RecordedAllocation.writeLedger(file, null)).isInstanceOf(NullPointerException.class);
        assertThat(Files.readString(file)).isEqualTo(reserved);
        String completed = "[{\"id\":\"1\",\"accounted_usd\":0.025}]";
        RecordedAllocation.writeLedger(file, completed);
        assertThat(Files.readString(file)).isEqualTo(completed);
        try (var files = Files.list(temporary)) { assertThat(files.toList()).containsExactly(file); }
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "[{\"id\":\"x\"}]", "[{\"id\":\"x\",\"accounted_usd\":-1}]",
            "[{\"id\":\"x\",\"accounted_usd\":0},{\"id\":\"x\",\"accounted_usd\":0}]"})
    void invalidAccountingFailsClosed(String ledger) throws Exception {
        Files.writeString(temporary.resolve("ledger.json"), ledger);
        assertThatThrownBy(() -> RecordedAllocation.accountedTotal(temporary, json)).isInstanceOf(IllegalArgumentException.class);
    }
}
