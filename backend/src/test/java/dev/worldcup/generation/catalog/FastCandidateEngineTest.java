package dev.worldcup.generation.catalog;

import static dev.worldcup.generation.catalog.CatalogComposer.*;
import static org.assertj.core.api.Assertions.*;

import dev.worldcup.candidate.CandidateModels.Candidate;
import dev.worldcup.candidate.CandidateModels.VerificationMode;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.generation.engine.InvalidModelOutput;
import dev.worldcup.shared.Failure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FastCandidateEngineTest {
    private static final Instant NOW = Instant.parse("2026-09-19T00:00:00Z");
    private static final String UNIT = "지속 가능한 취미";
    private final FakeCatalog catalog = new FakeCatalog();
    private final FakeComposer composer = new FakeComposer();
    private final FastCandidateEngine engine = new FastCandidateEngine(catalog, composer, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofSeconds(60));

    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void exactPresetProducesRequestedSizeWithoutAiOrCertificate(int size) {
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries(36)));
        var result = engine.generate(input(size), context());
        assertThat(result.candidates().candidates()).hasSize(size);
        assertThat(result.candidates().candidates()).extracting(Candidate::id)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, size).mapToObj(i -> "c" + i).toList());
        assertThat(result.certificate()).isNull();
        assertThat(result.validatorVersion()).isEqualTo("fast-best-effort-v1");
        assertThat(result.providerVersion()).isEqualTo("catalog/curated-v1");
        assertThat(result.candidates().plan().groundingRequired()).isFalse();
        assertThat(composer.calls).isZero();
        assertThat(catalog.searches).isZero();
    }

    @Test void sameJobPresetSelectionIsDeterministicAndHasNoDuplicateFamily() {
        var entries = new ArrayList<>(entries(16));
        entries.add(new CatalogEntry("variant", "hobby", UNIT, "family-1", "변형 이름", List.of()));
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries));
        var first = engine.generate(input(16), context());
        assertThat(engine.generate(input(16), context()).candidates().candidates()).isEqualTo(first.candidates().candidates());
        var names = first.candidates().candidates().stream().map(Candidate::name).toList();
        assertThat(names.stream().filter(name -> name.equals("후보1") || name.equals("변형 이름")).count()).isEqualTo(1);
    }
    @Test void recoveredWorkerCannotRepeatAnUncertainPaidComposition() {
        composer.result = selection(0, 16);
        var recovered = new CandidateEngine.Context("job", 2, NOW.plusSeconds(120), List.of());
        assertThatThrownBy(() -> engine.generate(input(16), recovered)).isInstanceOfSatisfying(Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(Failure.Code.PROVIDER_UNAVAILABLE));
        assertThat(composer.calls).isZero();
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries(16)));
        assertThat(engine.generate(input(16), recovered).candidates().candidates()).hasSize(16);
        assertThat(composer.calls).isZero();
    }

    @Test void regenerationPrefersUnseenPresetCardsAndDoesNotOnlyReorder() {
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries(16)));
        var previous = entries(8).stream().map(CatalogEntry::name).toList();
        var result = engine.generate(input(8), context(List.of(), previous));
        assertThat(result.candidates().candidates()).extracting(Candidate::name).doesNotContainAnyElementsOf(previous);
        assertThat(composer.calls).isZero();
    }

    @Test void identicalPresetCannotServeRegenerationAndFallsBackToExactlyOneComposition() {
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries(8)));
        composer.result = selection(0, 8);
        var previous = entries(8).stream().map(CatalogEntry::name).toList();
        var result = engine.generate(input(8), context(List.of(), previous));
        assertThat(result.candidates().candidates()).extracting(Candidate::name).contains("새 후보1");
        assertThat(composer.calls).isEqualTo(1);
        assertThat(composer.previous).isEqualTo(previous);
    }

    @Test void sparsePresetDoesNotPretendAFullMatch() {
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries(3)));
        composer.result = selection(0, 8);
        assertThat(engine.generate(input(8), context()).candidates().candidates()).hasSize(8);
        assertThat(composer.calls).isEqualTo(1);
    }

    @Test void directPreferencesRequireOneCompositionEvenForPresetAndAreBounded() {
        catalog.preset = Optional.of(new CatalogPreset("curated-v1", UNIT, entries(16)));
        var history = IntStream.range(0, 60).mapToObj(i -> new CandidateEngine.Preference(UNIT, "선택" + i, "거절" + i, NOW)).toList();
        composer.result = selection(0, 8);
        engine.generate(input(8), context(history, List.of()));
        assertThat(composer.calls).isEqualTo(1);
        assertThat(composer.history).hasSize(50);
        assertThat(catalog.presetLookups).isZero();
    }

    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void emptyPoolGeneratesAllInOneCallWithoutReviewRepairOrEvidence(int size) {
        composer.result = selection(0, size);
        var result = engine.generate(input(size), context());
        assertThat(result.candidates().candidates()).hasSize(size);
        assertThat(result.certificate()).isNull();
        assertThat(result.providerVersion()).isEqualTo("fake-compact");
        assertThat(composer.calls).isEqualTo(1);
        assertThat(composer.call.stage()).isEqualTo("COMPOSE");
        assertThat(composer.call.deadline()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test void partialPoolKeepsDatabaseDisplayTextAndOnlyAddsMissingCandidates() {
        catalog.rows = entries(4);
        composer.result = selection(4, 4);
        var result = engine.generate(input(8), context());
        assertThat(result.candidates().candidates()).extracting(Candidate::name)
                .startsWith("후보1", "후보2", "후보3", "후보4");
        assertThat(result.candidates().candidates().getFirst().tags()).isEqualTo(List.of("기존 태그"));
        assertThat(composer.calls).isEqualTo(1);
        assertThat(result.candidates().plan().coverage()).hasSize(2);
    }

    @Test void fullButNonExactPoolStillRequiresOneRequestInterpretationCall() {
        catalog.rows = entries(16);
        composer.result = selection(8, 0);
        engine.generate(new GenerationInput("취미 추천해줘 단 운동은 싫어", 8, "ko-KR", "Asia/Seoul"), context());
        assertThat(composer.calls).isEqualTo(1);
    }

    @Test void retrievesAtMost64AndDoesNotLeakRawHistoryToDatabaseLookup() {
        catalog.rows = entries(80);
        composer.result = selection(8, 0);
        engine.generate(input(8), context());
        assertThat(catalog.limit).isEqualTo(64);
        assertThat(composer.available).hasSize(64);
        assertThat(catalog.prompt).isEqualTo(input(8).prompt());
    }

    @ParameterizedTest @ValueSource(strings = {"unknown-id", "duplicate-id", "count", "wrong-unit", "duplicate-family", "duplicate-name", "invented-constraint", "duplicate-constraint", "blank-name", "long-tag", "blank-family", "blank-category", "null-decision"})
    void rejectsMalformedSingleResponseWithoutSecondCall(String defect) {
        catalog.rows = entries(4);
        var normal = selection(4, 4);
        var ids = new ArrayList<>(normal.selectedIds());
        var additions = new ArrayList<>(normal.additions());
        var constraints = List.<String>of();
        String unit = UNIT;
        Decision decision = Decision.READY;
        switch (defect) {
            case "unknown-id" -> ids.set(0, "not-supplied");
            case "duplicate-id" -> ids.set(1, ids.getFirst());
            case "count" -> additions.removeLast();
            case "wrong-unit" -> unit = "실제 장소";
            case "duplicate-family" -> additions.set(0, new Addition("다른 이름", List.of(), " FAMILY-1 ", "새 범주"));
            case "duplicate-name" -> additions.set(0, new Addition(" 후보1 ", List.of(), "different-family", "새 범주"));
            case "invented-constraint" -> constraints = List.of("조건을 발명함");
            case "duplicate-constraint" -> constraints = List.of("취미", "취미");
            case "blank-name" -> additions.set(0, new Addition(" ", List.of(), "different-family", "새 범주"));
            case "long-tag" -> additions.set(0, new Addition("새 이름", List.of("가".repeat(41)), "different-family", "새 범주"));
            case "blank-family" -> additions.set(0, new Addition("새 이름", List.of(), " ", "새 범주"));
            case "blank-category" -> additions.set(0, new Addition("새 이름", List.of(), "different-family", " "));
            case "null-decision" -> decision = null;
        }
        composer.result = new Selection(decision, unit, constraints, ids, additions);
        fails(Failure.Code.QUALITY_GATE_FAILED, () -> engine.generate(input(8), context()));
        assertThat(composer.calls).isEqualTo(1);
    }

    @Test void constraintQuotesAreAttributionAndNotFabricatedIndependentPasses() {
        var proposal = selection(0, 8);
        composer.result = new Selection(Decision.READY, UNIT, List.of("운동 말고"), proposal.selectedIds(), proposal.additions());
        var result = engine.generate(new GenerationInput("운동 말고 취미 추천해줘", 8, "ko-KR", "Asia/Seoul"), context());
        assertThat(result.candidates().plan().hardConstraints()).hasSize(1);
        assertThat(result.candidates().plan().hardConstraints().getFirst().mode()).isEqualTo(VerificationMode.SEMANTIC_ESTIMATE);
        assertThat(result.certificate()).isNull();
    }

    @ParameterizedTest @ValueSource(strings = {"CLARIFICATION_REQUIRED", "GROUNDING_REQUIRED", "UNSUPPORTED_REQUEST"})
    void ambiguousUnsupportedOrLiveFactRequestsAreNotReplacedWithGenericIdeas(String decision) {
        composer.result = new Selection(Decision.valueOf(decision), "", List.of(), List.of(), List.of());
        var expected = "UNSUPPORTED_REQUEST".equals(decision) ? Failure.Code.UNSUPPORTED_REQUEST : Failure.Code.CLARIFICATION_REQUIRED;
        fails(expected, () -> engine.generate(new GenerationInput("오늘 영업하는 서울 데이트 장소", 8, "ko-KR", "Asia/Seoul"), context()));
        assertThat(composer.calls).isEqualTo(1);
    }

    @Test void unchangedWholeSetFromComposerFailsRatherThanCallingAgain() {
        catalog.rows = entries(8); composer.result = selection(8, 0);
        var previous = entries(8).stream().map(CatalogEntry::name).toList();
        fails(Failure.Code.QUALITY_GATE_FAILED, () -> engine.generate(input(8), context(List.of(), previous)));
        assertThat(composer.calls).isEqualTo(1);
    }

    @Test void invalidProviderOutputIsSafeAndNeverRetried() {
        composer.failure = new InvalidModelOutput();
        fails(Failure.Code.QUALITY_GATE_FAILED, () -> engine.generate(input(8), context()));
        assertThat(composer.calls).isEqualTo(1);
    }

    @Test void deadlineAndInterruptionApplyEvenToZeroCallPath() {
        catalog.preset = Optional.of(new CatalogPreset("curated", UNIT, entries(16)));
        fails(Failure.Code.PROVIDER_UNAVAILABLE, () -> engine.generate(input(8), new CandidateEngine.Context("job", 1, NOW.plusSeconds(1), List.of())));
        Thread.currentThread().interrupt();
        try { fails(Failure.Code.PROVIDER_UNAVAILABLE, () -> engine.generate(input(8), context())); }
        finally { Thread.interrupted(); }
        assertThat(composer.calls).isZero();
    }

    private GenerationInput input(int size) { return new GenerationInput("취미 추천해줘", size, "ko-KR", "Asia/Seoul"); }
    private CandidateEngine.Context context() { return context(List.of(), List.of()); }
    private CandidateEngine.Context context(List<CandidateEngine.Preference> history, List<String> previous) {
        return new CandidateEngine.Context("test-job", 1, NOW.plusSeconds(300), history, previous);
    }
    private static List<CatalogEntry> entries(int size) {
        return IntStream.rangeClosed(1, size).mapToObj(i -> new CatalogEntry("id" + i, "hobby", UNIT, "family-" + i,
                "후보" + i, List.of("기존 태그"))).toList();
    }
    private static Selection selection(int selected, int added) {
        return new Selection(Decision.READY, UNIT, List.of(), IntStream.rangeClosed(1, selected).mapToObj(i -> "id" + i).toList(),
                IntStream.rangeClosed(1, added).mapToObj(i -> new Addition("새 후보" + i, List.of(), "new-family-" + i, "새 범주")).toList());
    }
    private static void fails(Failure.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(Failure.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    private static final class FakeCatalog implements CandidateCatalog {
        List<CatalogEntry> rows = List.of(); Optional<CatalogPreset> preset = Optional.empty();
        int limit; int searches; int presetLookups; String prompt;
        public List<CatalogEntry> candidates(String prompt, int limit) { searches++; this.prompt = prompt; this.limit = limit; return rows; }
        public Optional<CatalogPreset> preset(GenerationInput input) { presetLookups++; return preset; }
    }
    private static final class FakeComposer implements CatalogComposer {
        int calls; Selection result; CallContext call; RuntimeException failure;
        List<CatalogEntry> available; List<CandidateEngine.Preference> history; List<String> previous;
        public Composed compose(GenerationInput input, List<CatalogEntry> available, List<CandidateEngine.Preference> history,
                                List<String> previous, CallContext context) {
            calls++; this.call = context; this.available = available; this.history = history; this.previous = previous;
            if (failure != null) throw failure;
            return new Composed(result, "fake-compact");
        }
    }
}
