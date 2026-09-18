package dev.worldcup.generation;

import dev.worldcup.shared.Failure;
import dev.worldcup.generation.reuse.CandidateReuseService;
import dev.worldcup.generation.reuse.ReusePolicy;
import dev.worldcup.tournament.TournamentRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Short DB transactions surround provider work. Attempt fencing rejects stale completion. */
@Service
public class GenerationWorker {
    private final GenerationRepository jobs;
    private final TournamentRepository history;
    private final CandidateEngine engine;
    private final CandidateReuseService reuse;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final SecureRandom random;
    public GenerationWorker(GenerationRepository jobs, TournamentRepository history, CandidateEngine engine, CandidateReuseService reuse,
                            TransactionTemplate transaction, Clock clock, SecureRandom random) {
        this.jobs = jobs; this.history = history; this.engine = engine; this.reuse = reuse;
        this.transaction = transaction; this.clock = clock; this.random = random;
    }
    public void recoverExpired() {
        transaction.executeWithoutResult(status -> {
            var now = clock.instant();
            for (var job : jobs.expired(now)) jobs.recover(job, now);
        });
    }
    public boolean runOne() {
        Optional<GenerationRepository.Job> claimed = transaction.execute(status -> jobs.claim(clock.instant()));
        if (claimed == null || claimed.isEmpty()) return false;
        var job = claimed.get();
        try {
            var choices = history.recentDirectChoices(job.actorId(), clock.instant().minus(Duration.ofDays(30)));
            var context = new CandidateEngine.Context(job.id(), job.attempt(), job.leaseUntil(), choices);
            String previousMembership = job.draftId() == null ? null : jobs.ownedDraft(job.actorId(), job.draftId(), false)
                    .map(draft -> ReusePolicy.membershipHash(draft.content().candidates().stream().map(c -> c.name()).toList()))
                    .orElseThrow(() -> Failure.of(Failure.Code.QUALITY_GATE_FAILED));
            String previousCoreActivities = job.draftId() == null ? null
                    : reuse.previousCoreActivities(job.actorId(), job.draftId(), previousMembership).orElse(null);
            var hit = reuse.find(job.input(), context, previousMembership, previousCoreActivities);
            var generated = hit.map(CandidateReuseService.ReuseHit::generated).orElseGet(() -> engine.generate(job.input(), context));
            if (generated.candidates().plan().size() != job.input().size()) throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
            if (previousMembership != null && previousMembership.equals(ReusePolicy.membershipHash(
                    generated.candidates().candidates().stream().map(c -> c.name()).toList()))
                    || previousCoreActivities != null && previousCoreActivities.equals(hit.map(CandidateReuseService.ReuseHit::coreActivityHash)
                        .orElseGet(() -> ReusePolicy.coreActivityHash(generated.certificate())))) {
                throw Failure.of(Failure.Code.QUALITY_GATE_FAILED);
            }
            var content = DraftContent.from(generated, random);
            transaction.executeWithoutResult(status -> {
                hit.ifPresent(reuse::requireCurrentApproval);
                if (jobs.complete(job, content, generated, clock.instant())) {
                    if (hit.isPresent()) reuse.recordUse(hit.get(), job);
                    else reuse.stageSuccessful(job, context, generated);
                }
            });
        } catch (Failure failure) {
            transaction.executeWithoutResult(status -> jobs.fail(job, failure.code(), clock.instant()));
        } catch (RuntimeException failure) {
            // Provider messages can contain prompts or keys. Expose/store a safe code only.
            transaction.executeWithoutResult(status -> jobs.fail(job, Failure.Code.PROVIDER_UNAVAILABLE, clock.instant()));
        }
        return true;
    }
}
