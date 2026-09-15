package dev.worldcup.generation.engine;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.generation.engine.EngineModels.*;

import dev.worldcup.candidate.CandidateQualityGate;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.shared.Failure;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

/** One fixed plan, one initial set, at most one repair; nothing unvalidated escapes. */
public final class StagedCandidateEngine implements CandidateEngine {
    private final EngineStages stages;
    private final Clock clock;
    private final Duration operationTimeout;
    private final CandidateQualityGate gate = new CandidateQualityGate(Duration.ofHours(24));

    public StagedCandidateEngine(EngineStages stages, Clock clock, Duration operationTimeout) {
        if (operationTimeout == null || operationTimeout.isNegative() || operationTimeout.isZero()) {
            throw new IllegalArgumentException("Positive engine timeout required");
        }
        this.stages = stages; this.clock = clock; this.operationTimeout = operationTimeout;
    }

    @Override public Generated generate(GenerationInput input, Context context) {
        Instant now = clock.instant();
        Instant deadline = context.deadline().minusSeconds(2);
        if (deadline.isAfter(now.plus(operationTimeout))) deadline = now.plus(operationTimeout);
        var run = new Run(context, deadline);
        try {
            var proposed = stages.plan(input, context.recentDirectChoices().stream().limit(50).toList(), now, run.call("PLAN")).value();
            var fixed = freeze(input, now, proposed);
            Batch original;
            String generationVersion;
            try {
                var generated = stages.generate(fixed, run.call("GENERATE"));
                original = generated.value(); generationVersion = generated.version();
            } catch (InvalidModelOutput malformed) {
                original = new Batch(List.of()); generationVersion = "invalid-initial-output";
            }
            var inspection = inspect(fixed, original, run, "INITIAL");
            if (inspection.validated() != null) return finish(input, inspection, generationVersion, run);

            var replacementIds = replacementIds(input.size(), original, inspection.findings());
            var repaired = stages.repair(fixed, original, replacementIds, inspection.findings(), run.call("REPAIR"));
            preserveUnchanged(original, repaired.value(), replacementIds);
            var checked = inspect(fixed, repaired.value(), run, "REPAIRED");
            if (checked.validated() == null) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
            return finish(input, checked, repaired.version(), run);
        } catch (InvalidModelOutput invalid) {
            throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        }
    }

    private FixedPlan freeze(GenerationInput input, Instant now, PlanProposal specification) {
        if (specification.decision() == Decision.CLARIFICATION_REQUIRED) throw Failure.of(Failure.Code.CLARIFICATION_REQUIRED);
        if (specification.decision() == Decision.UNSUPPORTED_REQUEST) throw Failure.of(Failure.Code.UNSUPPORTED_REQUEST);
        if (specification.decision() != Decision.READY || blank(specification.unit()) || specification.unit().length() > 120
                || specification.constraints().size() > 12 || specification.coverage().isEmpty()
                || specification.softPreferences().size() > 12) throw new InvalidModelOutput();
        Set<String> constraintIds = new HashSet<>(), bucketIds = new HashSet<>();
        for (var constraint : specification.constraints()) {
            if (!identifier(constraint.id()) || "availability".equals(constraint.id()) || !constraintIds.add(constraint.id())
                    || blank(constraint.description()) || constraint.description().length() > 300
                    || blank(constraint.sourceText()) || !normalize(input.prompt()).contains(normalize(constraint.sourceText()))
                    || constraint.mode() == null) throw new InvalidModelOutput();
        }
        long total = 0;
        for (var bucket : specification.coverage()) {
            if (!identifier(bucket.id()) || !bucketIds.add(bucket.id()) || blank(bucket.description())
                    || bucket.description().length() > 300 || bucket.quota() <= 0 || bucket.quota() > input.size()) throw new InvalidModelOutput();
            total += bucket.quota();
        }
        if (total != input.size() || specification.softPreferences().stream().anyMatch(s -> blank(s) || s.length() > 300)) throw new InvalidModelOutput();
        var plan = new Plan(input.size(), specification.unit(), specification.groundingRequired(),
                specification.constraints().stream().map(c -> new HardConstraint(c.id(), c.mode())).toList(),
                specification.coverage().stream().map(b -> new CoverageBucket(b.id(), b.quota())).toList());
        return new FixedPlan(input, now, specification, plan);
    }

    private Inspection inspect(FixedPlan plan, Batch batch, Run run, String phase) {
        var findings = basicFindings(plan, batch);
        if (!findings.isEmpty()) return new Inspection(null, findings, "not-reviewed");
        Grounding grounding = plan.specification().groundingRequired()
                || plan.specification().constraints().stream().anyMatch(c -> c.mode() == VerificationMode.GROUNDED_FACT)
                ? stages.ground(plan, batch, run.call("GROUND_" + phase)) : Grounding.empty();
        var result = stages.review(plan, batch, grounding, run.call("REVIEW_" + phase));
        Review review = result.value();
        // Candidate repair must never hide omitted constraints or an invalid interpretation.
        if (review.planFaithful() != Verdict.PASS) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        Set<String> ids = batch.candidates().stream().map(Proposal::id).collect(java.util.stream.Collectors.toSet());
        for (var finding : review.findings()) {
            if (blank(finding.code()) || blank(finding.detail()) || !ids.containsAll(finding.candidateIds())) throw new InvalidModelOutput();
        }
        findings.addAll(review.findings());
        if (review.candidateQuality() != Verdict.PASS && findings.isEmpty()) {
            findings.add(new Finding("CANDIDATE_QUALITY", List.of(), "Independent quality review did not pass"));
        }
        var evidence = new Evidence(review.assessments(), grounding.facts(), review.comparable(), review.noSemanticDuplicates(), result.version());
        var checked = gate.validate(plan.gatePlan(), displayCandidates(plan, batch), evidence, clock.instant());
        for (var issue : checked.issues()) {
            var targets = issue.candidateId() == null ? List.<String>of() : List.of(issue.candidateId());
            if (issue.code() == CandidateQualityGate.Code.SEMANTIC_REVIEW_FAILED && !review.findings().isEmpty()
                    && review.findings().stream().noneMatch(f -> f.candidateIds().isEmpty())) {
                targets = review.findings().stream().flatMap(f -> f.candidateIds().stream()).distinct().toList();
            }
            findings.add(new Finding(issue.code().name(), targets, issue.detail()));
        }
        return new Inspection(findings.isEmpty() ? checked.validated().orElse(null) : null, findings, result.version());
    }

    private List<Finding> basicFindings(FixedPlan plan, Batch batch) {
        var findings = new ArrayList<Finding>();
        Set<String> expected = new HashSet<>(allIds(plan.input().size()));
        Set<String> ids = new HashSet<>();
        for (var proposal : batch.candidates()) {
            if (proposal == null || !expected.contains(proposal.id()) || !ids.add(proposal.id())) {
                findings.add(new Finding("INVALID_CANDIDATE_IDS", List.of(), "Use each fixed candidate ID exactly once")); break;
            }
            if (blank(proposal.name()) || proposal.name().length() > 100 || proposal.tags().size() > 2
                    || proposal.tags().stream().anyMatch(t -> blank(t) || t.length() > 40)
                    || blank(proposal.coreActivity()) || blank(proposal.description()) || blank(proposal.repeatability()) || blank(proposal.requirements())
                    || proposal.coreActivity().length() > 120 || proposal.description().length() > 240
                    || proposal.repeatability().length() > 240 || proposal.requirements().length() > 300) {
                findings.add(new Finding("INVALID_CANDIDATE", List.of(proposal.id()), "Candidate fields missing or too long"));
            }
        }
        if (!ids.equals(expected)) findings.add(new Finding("COUNT_OR_IDS", List.of(), "Exact requested count and fixed IDs required"));
        // Reuse the deterministic gate; review-only errors are deliberately deferred to independent assessment.
        if (findings.isEmpty()) {
            var structural = gate.validate(plan.gatePlan(), displayCandidates(plan, batch),
                    new Evidence(List.of(), List.of(), Verdict.UNKNOWN, Verdict.UNKNOWN, "pending"), clock.instant());
            for (var issue : structural.issues()) {
                if (!Set.of(CandidateQualityGate.Code.HARD_CONSTRAINT_UNVERIFIED, CandidateQualityGate.Code.GROUNDING_UNVERIFIED,
                        CandidateQualityGate.Code.SEMANTIC_REVIEW_FAILED).contains(issue.code())) {
                    findings.add(new Finding(issue.code().name(), issue.candidateId() == null ? List.of() : List.of(issue.candidateId()), issue.detail()));
                }
            }
        }
        return findings;
    }

    private List<Candidate> displayCandidates(FixedPlan plan, Batch batch) {
        return batch.candidates().stream().map(c -> new Candidate(c.id(), c.name(), plan.gatePlan().unit(), c.bucketId(), c.tags(), null)).toList();
    }
    private List<String> replacementIds(int size, Batch original, List<Finding> findings) {
        if (original.candidates().size() != size || findings.stream().anyMatch(f -> f.candidateIds().isEmpty())) return allIds(size);
        var ids = new java.util.LinkedHashSet<String>();
        findings.forEach(f -> ids.addAll(f.candidateIds()));
        return ids.isEmpty() ? allIds(size) : List.copyOf(ids);
    }
    private void preserveUnchanged(Batch original, Batch repaired, List<String> replacements) {
        var retained = original.candidates().stream().filter(c -> !replacements.contains(c.id())).toList();
        for (var candidate : retained) {
            if (repaired.candidates().stream().noneMatch(candidate::equals)) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        }
    }
    private Generated finish(GenerationInput input, Inspection checked, String providerVersion, Run run) {
        run.call("COMPLETE");
        return new Generated(checked.validated(), input.size() + "강 선택 월드컵", providerVersion, checked.reviewerVersion());
    }
    public static List<String> allIds(int size) { return IntStream.rangeClosed(1, size).mapToObj(i -> "c" + i).toList(); }
    private static boolean identifier(String value) { return value != null && value.matches("[a-z][a-z0-9_-]{0,39}"); }
    private static String normalize(String value) { return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip(); }
    private static boolean blank(String value) { return normalize(value).isEmpty(); }
    private record Inspection(CandidateQualityGate.ValidatedSet validated, List<Finding> findings, String reviewerVersion) {}
    private final class Run {
        private final Context context; private final Instant deadline;
        private Run(Context context, Instant deadline) { this.context = context; this.deadline = deadline; }
        private CallContext call(String stage) {
            if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            return new CallContext(context.jobId(), context.attempt(), stage, deadline);
        }
    }
}
