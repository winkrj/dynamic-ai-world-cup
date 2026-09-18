package dev.worldcup.generation.reuse;

import static dev.worldcup.candidate.CandidateModels.*;
import static dev.worldcup.generation.reuse.CandidateReuseRepository.*;

import dev.worldcup.candidate.CandidateQualityGate;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.GenerationRepository;
import dev.worldcup.shared.Failure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB-first exact complete sets. Approval is an explicit trusted operator action, never a public API. */
@Service
public class CandidateReuseService {
    public record ReuseHit(String setId, CandidateEngine.Generated generated, String coreActivityHash) {}
    private final CandidateReuseRepository repository;
    private final Clock clock;
    private final CandidateQualityGate gate = new CandidateQualityGate(Duration.ofHours(24));
    public CandidateReuseService(CandidateReuseRepository repository, Clock clock) {
        this.repository = repository; this.clock = clock;
    }

    public Optional<ReuseHit> find(GenerationInput input, CandidateEngine.Context context, String excludedMembership) {
        return find(input, context, excludedMembership, null);
    }
    public Optional<ReuseHit> find(GenerationInput input, CandidateEngine.Context context, String excludedMembership, String excludedCoreActivities) {
        if (!context.recentDirectChoices().isEmpty()) return Optional.empty();
        Instant now = clock.instant();
        String requestHash = ReusePolicy.contextHash(input);
        for (var set : repository.approved(requestHash, now)) {
            if (!approvedNow(set, now)
                    || !requestHash.equals(set.contextHash()) || !ReusePolicy.VERSION.equals(set.policyVersion())
                    || Objects.equals(excludedMembership, set.membershipHash())) continue;
            var certificate = set.certificate();
            var validated = validate(certificate, now);
            if (validated.isEmpty() || certificate.plan().size() != input.size()
                    || !set.membershipHash().equals(membership(certificate))
                    || Objects.equals(excludedCoreActivities, ReusePolicy.coreActivityHash(certificate))
                    || !set.validatorVersion().equals(certificate.evidence().reviewerVersion())) continue;
            // Recreate gate-issued type from ORIGINAL evidence, never synthesize fresh PASS evidence.
            return Optional.of(new ReuseHit(set.id(), new CandidateEngine.Generated(validated.get(), set.publicTitle(),
                    set.providerVersion(), set.validatorVersion()), ReusePolicy.coreActivityHash(certificate)));
        }
        return Optional.empty();
    }

    /** Optional richer comparison for a private draft whose original certificate is still retained. */
    public Optional<String> previousCoreActivities(String actorId, String draftId, String expectedMembership) {
        return repository.forDraft(actorId, draftId)
                .filter(set -> expectedMembership.equals(set.membershipHash()) && validate(set.certificate(), clock.instant()).isPresent())
                .map(set -> ReusePolicy.coreActivityHash(set.certificate()));
    }

    /** Caller must have just completed this exact attempt in the same DB transaction. */
    public void stageSuccessful(GenerationRepository.Job job, CandidateEngine.Context context,
                                CandidateEngine.Generated generated) {
        if (!context.recentDirectChoices().isEmpty() || generated.certificate() == null) return;
        Instant now = clock.instant();
        var certificate = generated.certificate();
        if (validate(certificate, now).isEmpty() || !certificate.plan().equals(generated.candidates().plan())
                || !generated.validatorVersion().equals(certificate.evidence().reviewerVersion())
                || !certificate.candidates().equals(generated.candidates().candidates())) return;
        repository.stage(new StoredSet(UUID.randomUUID().toString(), ReusePolicy.contextHash(job.input()), membership(certificate),
                ReusePolicy.VERSION, job.id(), job.attempt(), State.PENDING, generated.publicTitle(),
                generated.providerVersion(), generated.validatorVersion(), certificate, now, null, false, false, false));
    }

    public void recordUse(ReuseHit hit, GenerationRepository.Job job) {
        repository.recordUse(hit.setId(), job.id(), job.attempt(), clock.instant());
    }

    /** Lock approval through completion, so concurrent revoke/expiry cannot publish a stale hit. */
    public void requireCurrentApproval(ReuseHit hit) {
        var set = repository.get(hit.setId(), true).orElseThrow(() -> Failure.of(Failure.Code.QUALITY_GATE_FAILED));
        Instant now = clock.instant();
        if (!approvedNow(set, now) || validate(set.certificate(), now).isEmpty()) {
            throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
        }
    }

    @Transactional public void approve(String id, Approval approval) {
        Instant now = clock.instant();
        requireText(approval.operatorId(), 100); requireText(approval.qualityReview(), 1000);
        requireText(approval.publicSafetyReview(), 1000);
        var set = repository.get(id, true).orElseThrow(() -> new IllegalArgumentException("Unknown reuse set"));
        if (set.state() != State.PENDING || validate(set.certificate(), now).isEmpty()
                || !approval.timeIndependent() || approval.expiresAt() == null
                || !approval.expiresAt().isAfter(now) || approval.expiresAt().isAfter(now.plus(ReusePolicy.MAX_APPROVAL))
                || approval.expiresAt().isAfter(set.certificate().referenceTime().plus(ReusePolicy.MAX_EVIDENCE_AGE))
                || !ReusePolicy.VERSION.equals(set.policyVersion())
                || !set.validatorVersion().equals(set.certificate().evidence().reviewerVersion())
                || !set.membershipHash().equals(membership(set.certificate()))) {
            throw new IllegalArgumentException("Only current, fully reviewed, stable PENDING sets with bounded expiry can be approved");
        }
        repository.approve(id, approval, now);
    }
    @Transactional public void reject(String id, String operatorId, String reason) {
        transition(id, State.PENDING, State.REJECTED, operatorId, reason);
    }
    @Transactional public void revoke(String id, String operatorId, String reason) {
        transition(id, State.APPROVED, State.REVOKED, operatorId, reason);
    }
    public List<StoredSet> pending() { return repository.pending(); }
    public StoredSet inspect(String id) {
        return repository.get(id, false).orElseThrow(() -> new IllegalArgumentException("Unknown reuse set"));
    }
    private void transition(String id, State expected, State next, String operatorId, String reason) {
        requireText(operatorId, 100); requireText(reason, 1000);
        var set = repository.get(id, true).orElseThrow(() -> new IllegalArgumentException("Unknown reuse set"));
        if (set.state() != expected) throw new IllegalArgumentException("Invalid reuse review transition");
        repository.transition(id, next, operatorId, reason, clock.instant());
    }

    private Optional<CandidateQualityGate.ValidatedSet> validate(ValidationCertificate c, Instant now) {
        try {
            if (c == null || !ReusePolicy.VERSION.equals(c.policyVersion()) || c.referenceTime() == null || c.validatedAt() == null
                    || c.referenceTime().isAfter(c.validatedAt()) || c.validatedAt().isAfter(now)
                    || !c.referenceTime().isAfter(now.minus(ReusePolicy.MAX_EVIDENCE_AGE))
                    || c.plan().groundingRequired() || c.plan().hardConstraints().stream().anyMatch(h -> h.mode() == VerificationMode.GROUNDED_FACT)
                    || !c.evidence().facts().isEmpty() || !faithful(c.allocationInterpretation())
                    || c.allocationComparable() != Verdict.PASS || c.allocationNoSemanticDuplicates() != Verdict.PASS
                    || c.allocationFeasible() != Verdict.PASS || !faithful(c.finalReview().interpretation())
                    || c.finalReview().candidateQuality() != Verdict.PASS || !c.finalReview().findings().isEmpty()
                    || c.finalReview().comparable() != c.evidence().comparable()
                    || c.finalReview().noSemanticDuplicates() != c.evidence().noSemanticDuplicates()
                    || !c.finalReview().assessments().equals(c.evidence().assessments())) return Optional.empty();
            var ids = c.candidates().stream().map(Candidate::id).collect(java.util.stream.Collectors.toSet());
            var assessed = new HashSet<String>();
            for (var assessment : c.finalReview().feasibility()) {
                if (!ids.contains(assessment.candidateId()) || !assessed.add(assessment.candidateId())
                        || assessment.verdict() != Verdict.PASS || blank(assessment.reason()) || assessment.reason().length() > 300) return Optional.empty();
            }
            if (!ids.equals(assessed) || c.richCandidates().size() != c.candidates().size()) return Optional.empty();
            var richIds = new HashSet<String>();
            for (var rich : c.richCandidates()) {
                if (!richIds.add(rich.id()) || blank(rich.intentId()) || blank(rich.coreActivity()) || blank(rich.description())
                        || blank(rich.repeatability()) || blank(rich.requirements())
                        || rich.coreActivity().length() > 120 || rich.description().length() > 240
                        || rich.repeatability().length() > 240 || rich.requirements().length() > 300
                        || c.candidates().stream().noneMatch(display -> display.id().equals(rich.id())
                            && display.name().equals(rich.name()) && display.bucketId().equals(rich.bucketId())
                            && display.tags().equals(rich.tags()))) return Optional.empty();
            }
            return gate.validate(c.plan(), c.candidates(), c.evidence(), now).validated();
        } catch (RuntimeException invalid) { return Optional.empty(); }
    }
    private static boolean faithful(dev.worldcup.generation.engine.EngineModels.InterpretationReview review) {
        return review != null && review.verdict() == Verdict.PASS && review.findings().isEmpty();
    }
    private static boolean approvedNow(StoredSet set, Instant now) {
        return set.state() == State.APPROVED && set.qualityApproved() && set.publicSafe() && set.timeIndependent()
                && ReusePolicy.VERSION.equals(set.policyVersion()) && set.expiresAt() != null && set.expiresAt().isAfter(now);
    }
    private static String membership(ValidationCertificate c) {
        return ReusePolicy.membershipHash(c.candidates().stream().map(Candidate::name).toList());
    }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void requireText(String value, int limit) {
        if (blank(value) || value.length() > limit) throw new IllegalArgumentException("Explicit bounded operator review text required");
    }
}
