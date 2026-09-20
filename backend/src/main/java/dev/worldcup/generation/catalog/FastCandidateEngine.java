package dev.worldcup.generation.catalog;

import static dev.worldcup.candidate.CandidateModels.*;

import dev.worldcup.candidate.CandidateQualityGate;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.generation.engine.InvalidModelOutput;
import dev.worldcup.shared.Failure;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

/** Curated exact preset or one compact composition. No online reviewer, search, repair or fallback. */
public final class FastCandidateEngine implements CandidateEngine {
    public static final String VALIDATOR_VERSION = "fast-best-effort-v1";
    public static final int MAX_RETRIEVAL = 64;
    private final CandidateCatalog catalog;
    private final CatalogComposer composer;
    private final Clock clock;
    private final Duration operationTimeout;
    private final CandidateQualityGate gate = new CandidateQualityGate(Duration.ofHours(24));

    public FastCandidateEngine(CandidateCatalog catalog, CatalogComposer composer, Clock clock, Duration operationTimeout) {
        if (operationTimeout == null || operationTimeout.isNegative() || operationTimeout.isZero()) {
            throw new IllegalArgumentException("Positive engine timeout required");
        }
        this.catalog = Objects.requireNonNull(catalog);
        this.composer = Objects.requireNonNull(composer);
        this.clock = Objects.requireNonNull(clock);
        this.operationTimeout = operationTimeout;
    }

    @Override public Generated generate(GenerationInput input, Context context) {
        Instant deadline = context.deadline().minusSeconds(2);
        if (deadline.isAfter(clock.instant().plus(operationTimeout))) deadline = clock.instant().plus(operationTimeout);
        checkDeadline(deadline);
        try {
            // Only the catalog's exact, curated request policy may skip natural-language interpretation.
            var preset = context.recentDirectChoices().isEmpty() ? catalog.preset(input) : java.util.Optional.<CatalogPreset>empty();
            if (preset.isPresent()) {
                var selected = selectPreset(preset.get(), input.size(), context);
                if (selected.size() == input.size()) {
                    return finish(input, preset.get().unit(), List.of(), selected,
                            "catalog/" + preset.get().id(), context.previousCandidateNames(), deadline);
                }
            }

            // After a worker crash the prior provider charge is uncertain; do not start another paid attempt.
            if (context.attempt() != 1) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            var available = catalog.candidates(input.prompt(), MAX_RETRIEVAL).stream().limit(MAX_RETRIEVAL).toList();
            var entries = index(available);
            checkDeadline(deadline);
            var composed = composer.compose(input, available, context.recentDirectChoices().stream().limit(50).toList(),
                    context.previousCandidateNames(), new CallContext(context.jobId(), context.attempt(), "COMPOSE", deadline));
            if (composed == null || composed.selection() == null || blank(composed.version())) throw new InvalidModelOutput();
            var selection = composed.selection();
            if (selection.decision() == CatalogComposer.Decision.CLARIFICATION_REQUIRED) {
                throw Failure.of(Failure.Code.CLARIFICATION_REQUIRED);
            }
            if (selection.decision() == CatalogComposer.Decision.GROUNDING_REQUIRED) throw Failure.of(Failure.Code.GROUNDING_REQUIRED);
            if (selection.decision() == CatalogComposer.Decision.UNSUPPORTED_REQUEST) throw Failure.of(Failure.Code.UNSUPPORTED_REQUEST);
            if (selection.decision() != CatalogComposer.Decision.READY || selection.selectedIds().size() > input.size()
                    || selection.additions().size() > input.size()) throw new InvalidModelOutput();

            var selected = new ArrayList<Card>();
            var usedIds = new HashSet<String>();
            for (String id : selection.selectedIds()) {
                CatalogEntry entry = entries.get(id);
                if (entry == null || !usedIds.add(id) || !normalize(selection.unit()).equals(normalize(entry.unit()))) {
                    throw new InvalidModelOutput();
                }
                selected.add(card(entry));
            }
            for (var addition : selection.additions()) {
                selected.add(new Card(addition.name(), addition.tags(), addition.family(), addition.category()));
            }
            return finish(input, selection.unit(), selection.constraintSources(), selected, composed.version(),
                    context.previousCandidateNames(), deadline);
        } catch (InvalidModelOutput invalid) {
            throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        }
    }

    private Map<String, CatalogEntry> index(List<CatalogEntry> entries) {
        var indexed = new LinkedHashMap<String, CatalogEntry>();
        for (var entry : entries) {
            if (entry == null || blank(entry.id()) || indexed.putIfAbsent(entry.id(), entry) != null) throw new InvalidModelOutput();
        }
        return indexed;
    }

    private List<Card> selectPreset(CatalogPreset preset, int size, Context context) {
        var shuffled = new ArrayList<>(preset.candidates());
        Collections.shuffle(shuffled, new Random(Objects.hash(context.jobId(), context.attempt(), preset.id())));
        // Prefer unseen cards, but an overlap is allowed as long as this is not the same entire set.
        Set<String> previous = normalizedNames(context.previousCandidateNames());
        shuffled.sort(java.util.Comparator.comparing(entry -> previous.contains(normalize(entry.name()))));
        var families = new HashSet<String>();
        var names = new HashSet<String>();
        var cards = new ArrayList<Card>();
        for (var entry : shuffled) {
            if (!normalize(preset.unit()).equals(normalize(entry.unit()))) continue;
            var card = card(entry);
            if (!validCard(card) || families.contains(normalize(card.family())) || names.contains(normalize(card.name()))) continue;
            families.add(normalize(card.family())); names.add(normalize(card.name()));
            cards.add(card);
            if (cards.size() == size) break;
        }
        if (cards.size() == size && sameNames(cards, previous)) return List.of();
        return cards;
    }

    private Generated finish(GenerationInput input, String unit, List<String> sources, List<Card> cards,
                             String providerVersion, List<String> previousNames, Instant deadline) {
        if (!bounded(unit, 120) || sources.size() > 12 || cards.size() != input.size()) throw new InvalidModelOutput();
        var uniqueSources = new HashSet<String>();
        var constraints = new ArrayList<HardConstraint>();
        for (String source : sources) {
            // Attribution only: quoting the request does not prove every condition was understood or met.
            if (!bounded(source, 500) || !input.prompt().contains(source) || !uniqueSources.add(normalize(source))) {
                throw new InvalidModelOutput();
            }
            constraints.add(new HardConstraint("constraint_" + (constraints.size() + 1), VerificationMode.SEMANTIC_ESTIMATE));
        }
        Set<String> families = new HashSet<>(), names = new HashSet<>();
        Map<String, String> categories = new LinkedHashMap<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        var candidates = new ArrayList<Candidate>();
        for (var card : cards) {
            if (!validCard(card) || !families.add(normalize(card.family())) || !names.add(normalize(card.name()))) {
                throw new InvalidModelOutput();
            }
            String bucket = categories.computeIfAbsent(normalize(card.category()), ignored -> "category_" + (categories.size() + 1));
            counts.merge(bucket, 1, Integer::sum);
            candidates.add(new Candidate("c" + (candidates.size() + 1), card.name(), unit, bucket, card.tags(), null));
        }
        if (sameNames(cards, normalizedNames(previousNames))) throw new InvalidModelOutput();
        var plan = new Plan(input.size(), unit, false, constraints,
                counts.entrySet().stream().map(entry -> new CoverageBucket(entry.getKey(), entry.getValue())).toList());
        var result = gate.acceptBestEffort(plan, candidates);
        if (!result.passed()) throw new InvalidModelOutput();
        checkDeadline(deadline);
        return new Generated(result.validated().orElseThrow(), input.size() + "강 선택 월드컵", providerVersion,
                VALIDATOR_VERSION, null);
    }

    private boolean validCard(Card card) {
        return bounded(card.name(), 100) && bounded(card.family(), 120) && bounded(card.category(), 80)
                && card.tags() != null && card.tags().size() <= 2 && card.tags().stream().allMatch(tag -> bounded(tag, 40));
    }
    private static Card card(CatalogEntry entry) { return new Card(entry.name(), entry.tags(), entry.family(), entry.topic()); }
    private static Set<String> normalizedNames(List<String> names) {
        var normalized = new HashSet<String>(); names.forEach(name -> normalized.add(normalize(name))); return normalized;
    }
    private static boolean sameNames(List<Card> cards, Set<String> previous) {
        return !previous.isEmpty() && normalizedNames(cards.stream().map(Card::name).toList()).equals(previous);
    }
    private void checkDeadline(Instant deadline) {
        if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
    }
    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
    private static boolean bounded(String value, int max) { return !blank(value) && value.length() <= max; }
    private static boolean blank(String value) { return normalize(value).isEmpty(); }
    private record Card(String name, List<String> tags, String family, String category) {}
}
