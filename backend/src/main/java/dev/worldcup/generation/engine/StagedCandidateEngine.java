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
            validateProposal(input, proposed);
            var allocation = stages.allocate(input, now, proposed, run.call("ALLOCATE")).value();
            validateAllocation(proposed, allocation);
            if (allocation.approvedIntentIds().size() < input.size()) {
                var patch = stages.repairIntents(input, now, proposed, allocation.rejections(), run.repairCall("REPAIR_INTENTS"));
                proposed = applyIntentRepairs(proposed, allocation.rejections(), patch.value());
                validateProposal(input, proposed);
                allocation = stages.allocate(input, now, proposed, run.call("ALLOCATE_REPAIRED")).value();
            }
            var fixed = freeze(input, now, proposed, allocation);
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
            var repaired = stages.repair(fixed, original, replacementIds, inspection.findings(), run.repairCall("REPAIR"));
            preserveUnchanged(original, repaired.value(), replacementIds);
            var checked = inspect(fixed, repaired.value(), run, "REPAIRED");
            if (checked.validated() == null) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
            return finish(input, checked, repaired.version(), run);
        } catch (InvalidModelOutput invalid) {
            throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        }
    }

    private void validateProposal(GenerationInput input, PlanProposal specification) {
        if (specification.decision() == Decision.CLARIFICATION_REQUIRED) throw Failure.of(Failure.Code.CLARIFICATION_REQUIRED);
        if (specification.decision() == Decision.UNSUPPORTED_REQUEST) throw Failure.of(Failure.Code.UNSUPPORTED_REQUEST);
        if (specification.decision() != Decision.READY || blank(specification.unit()) || specification.unit().length() > 120
                || specification.constraints().size() > 12 || specification.coverage().isEmpty() || specification.coverage().size() > input.size()
                || specification.intents().size() < input.size() || specification.intents().size() > input.size() + 4
                || specification.softPreferences().size() > 12) throw new InvalidModelOutput();
        Set<String> constraintIds = new HashSet<>(), bucketIds = new HashSet<>();
        for (var constraint : specification.constraints()) {
            if (!identifier(constraint.id()) || "availability".equals(constraint.id()) || !constraintIds.add(constraint.id())
                    || blank(constraint.description()) || constraint.description().length() > 300
                    || blank(constraint.sourceText()) || !normalize(input.prompt()).contains(normalize(constraint.sourceText()))
                    || constraint.mode() == null) throw new InvalidModelOutput();
        }
        for (var bucket : specification.coverage()) {
            if (!identifier(bucket.id()) || !bucketIds.add(bucket.id()) || blank(bucket.description())
                    || bucket.description().length() > 300) throw new InvalidModelOutput();
        }
        Set<String> intentIds = new HashSet<>();
        for (var intent : specification.intents()) {
            if (!identifier(intent.id()) || !intentIds.add(intent.id()) || !bucketIds.contains(intent.bucketId())
                    || blank(intent.coreActivity()) || intent.coreActivity().length() > 120
                    || blank(intent.fit()) || intent.fit().length() > 300) throw new InvalidModelOutput();
        }
        if (specification.softPreferences().stream().anyMatch(s -> blank(s) || s.length() > 300)) throw new InvalidModelOutput();
    }

    private void validateAllocation(PlanProposal specification, AllocationReview review) {
        if (review.planFaithful() != Verdict.PASS || review.comparable() != Verdict.PASS
                || review.noSemanticDuplicates() != Verdict.PASS || review.feasible() != Verdict.PASS) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        var poolIds = specification.intents().stream().map(ActivityIntent::id).collect(java.util.stream.Collectors.toSet());
        Set<String> assessed = new HashSet<>(review.approvedIntentIds());
        if (assessed.size() != review.approvedIntentIds().size() || !poolIds.containsAll(assessed)) throw new InvalidModelOutput();
        for (var rejection : review.rejections()) {
            if (!poolIds.contains(rejection.intentId()) || !assessed.add(rejection.intentId())
                    || blank(rejection.reason()) || rejection.reason().length() > 300) throw new InvalidModelOutput();
        }
        if (!assessed.equals(poolIds)) throw new InvalidModelOutput();
    }

    private PlanProposal applyIntentRepairs(PlanProposal original, List<IntentRejection> rejections, IntentRepairs patch) {
        var expected = rejections.stream().map(IntentRejection::intentId).collect(java.util.stream.Collectors.toSet());
        var bucketIds = original.coverage().stream().map(BucketSpec::id).collect(java.util.stream.Collectors.toSet());
        var intents = new java.util.LinkedHashMap<String, ActivityIntent>();
        original.intents().forEach(intent -> intents.put(intent.id(), intent));
        Set<String> replaced = new HashSet<>();
        for (var replacement : patch.replacements()) {
            if (!expected.contains(replacement.id()) || !replaced.add(replacement.id()) || !bucketIds.contains(replacement.bucketId())) throw new InvalidModelOutput();
            intents.put(replacement.id(), replacement);
        }
        if (!replaced.equals(expected)) throw new InvalidModelOutput();
        var coverage = original.coverage().stream().map(bucket -> new BucketSpec(bucket.id(), bucket.description(),
                intents.values().stream().filter(i -> bucket.id().equals(i.bucketId()))
                        .map(i -> new IntentSpec(i.id(), i.coreActivity(), i.fit())).toList())).toList();
        return new PlanProposal(original.decision(), original.unit(), original.hobby(), original.groundingRequired(),
                original.constraints(), coverage, original.softPreferences());
    }

    private FixedPlan freeze(GenerationInput input, Instant now, PlanProposal specification, AllocationReview review) {
        validateAllocation(specification, review);
        if (review.approvedIntentIds().size() < input.size()) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        var pool = specification.intents().stream().collect(java.util.stream.Collectors.toMap(ActivityIntent::id, i -> i));
        var approved = review.approvedIntentIds().stream().map(pool::get).toList();
        var counts = approved.stream().limit(input.size()).collect(java.util.stream.Collectors.groupingBy(
                ActivityIntent::bucketId, java.util.LinkedHashMap::new, java.util.stream.Collectors.counting()));
        var plan = new Plan(input.size(), specification.unit(), specification.groundingRequired(),
                specification.constraints().stream().map(c -> new HardConstraint(c.id(), c.mode())).toList(),
                counts.entrySet().stream().map(e -> new CoverageBucket(e.getKey(), Math.toIntExact(e.getValue()))).toList());
        return new FixedPlan(input, now, specification, plan, approved.stream().filter(i -> counts.containsKey(i.bucketId())).toList());
    }

    private Inspection inspect(FixedPlan plan, Batch batch, Run run, String phase) {
        var findings = basicFindings(plan, batch, "INITIAL".equals(phase));
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

    private List<Finding> basicFindings(FixedPlan plan, Batch batch, boolean initial) {
        var findings = new ArrayList<Finding>();
        Set<String> expected = new HashSet<>(allIds(plan.input().size()));
        Set<String> ids = new HashSet<>();
        Set<String> usedIntents = new HashSet<>();
        var approved = plan.approvedIntents().stream().collect(java.util.stream.Collectors.toMap(ActivityIntent::id, i -> i));
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
            var intent = approved.get(proposal.intentId());
            if (intent == null || !usedIntents.add(proposal.intentId()) || !intent.bucketId().equals(proposal.bucketId())
                    || !intent.coreActivity().equals(proposal.coreActivity())
                    || initial && !plan.approvedIntents().get(allIds(plan.input().size()).indexOf(proposal.id())).id().equals(proposal.intentId())) {
                findings.add(new Finding("INVALID_ACTIVITY_INTENT", List.of(proposal.id()),
                        "Use a distinct independently approved intent, preserving its bucket and core activity"));
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
        private boolean repairUsed;
        private Run(Context context, Instant deadline) { this.context = context; this.deadline = deadline; }
        private CallContext call(String stage) {
            if (Thread.currentThread().isInterrupted() || !clock.instant().isBefore(deadline)) throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE);
            return new CallContext(context.jobId(), context.attempt(), stage, deadline);
        }
        private CallContext repairCall(String stage) {
            if (repairUsed) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
            var context = call(stage);
            repairUsed = true;
            return context;
        }
    }
}
