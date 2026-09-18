package dev.worldcup.generation.reuse;

import static dev.worldcup.generation.reuse.CandidateReuseRepository.*;
import static dev.worldcup.shared.Failure.Code.*;
import static org.assertj.core.api.Assertions.*;

import dev.worldcup.generation.*;
import dev.worldcup.identity.ActorService;
import dev.worldcup.infrastructure.IdempotencyService;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.infrastructure.RetentionService;
import dev.worldcup.shared.Failure;
import dev.worldcup.sharing.SharingService;
import dev.worldcup.support.PostgresSupport;
import dev.worldcup.tournament.PlaySessionTest;
import dev.worldcup.tournament.TournamentService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real PostgreSQL verifies lifecycle only. All candidate/evidence fixtures are explicitly synthetic. */
@SpringBootTest
@Import(CandidateReusePersistenceTest.FixtureConfiguration.class)
class CandidateReusePersistenceTest extends PostgresSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired ActorService actors;
    @Autowired GenerationService generation;
    @Autowired GenerationWorker worker;
    @Autowired CandidateReuseService reuse;
    @Autowired CandidateReuseRepository repository;
    @Autowired FixtureEngine engine;
    @Autowired TournamentService tournaments;
    @Autowired SharingService sharing;
    @Autowired IdempotencyService idempotency;
    @Autowired RetentionService retention;
    @Autowired JsonCodec json;

    @BeforeEach void reset() {
        // Only PostgresSupport's disposable test database, never a developer/production database.
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota, candidate_reuse_set, candidate_reuse_review, candidate_reuse_use CASCADE");
        engine.reset();
    }
    GenerationInput input() { return ReuseFixtures.input(); }
    String actor() { return actors.issue().actorId(); }
    Draft ready(String actor) { return ready(actor, input()); }
    Draft ready(String actor, GenerationInput input) {
        var job = generation.create(actor, actor, input); assertThat(worker.runOne()).isTrue();
        assertThat(generation.job(actor, job.id()).state()).isEqualTo("READY");
        return generation.preview(actor, generation.job(actor, job.id()).draftId());
    }
    StoredSet onlyPending() { return reuse.pending().getFirst(); }
    void approve(String id) {
        reuse.approve(id, new Approval("synthetic-test-operator", "Synthetic lifecycle test only; no real quality approval",
                "Synthetic public-safety lifecycle fixture", true, clock.instant().plusSeconds(3600)));
    }
    int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    String legacySet(State state, String sourceJobId) {
        String id = UUID.randomUUID().toString();
        var now = Timestamp.from(clock.instant());
        boolean approved = state == State.APPROVED;
        // Deliberately use today's lookup key: an old certificate must miss even if it reaches the reader.
        jdbc.update("""
                INSERT INTO candidate_reuse_set(id, context_hash, membership_hash, policy_version, source_job_id, source_attempt,
                    state, public_title, provider_version, validator_version, certificate, created_at, updated_at,
                    approved_at, expires_at, quality_approved, public_safe, time_independent)
                VALUES (?, ?, ?, 'approved-complete-set-v3-engine-v22', ?, 1, ?, '합성 이전 버전 세트',
                    'synthetic-generator', 'synthetic-independent-review', ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
                """, id, ReusePolicy.contextHash(input()), ReusePolicy.membershipHash(ReuseFixtures.generated().candidates().candidates().stream()
                        .map(c -> c.name()).toList()), sourceJobId, state.name(), ReuseFixtures.LEGACY_V3_CERTIFICATE, now, now,
                approved ? now : null, approved ? Timestamp.from(clock.instant().plusSeconds(3600)) : null,
                approved, approved, approved);
        return id;
    }
    String certificateJson(String id) {
        return jdbc.queryForObject("SELECT certificate::text FROM candidate_reuse_set WHERE id = ?", String.class, id);
    }

    @Test void readyDoesNotAutoApproveAndOnlyExplicitlyApprovedExactSetAvoidsEngine() {
        ready(actor()); var first = onlyPending();
        assertThat(first.state()).isEqualTo(State.PENDING); assertThat(first.qualityApproved()).isFalse();
        ready(actor()); assertThat(engine.calls.get()).isEqualTo(2); // PENDING always falls back once.
        approve(first.id()); int before = engine.calls.get();
        var reused = ready(actor());
        assertThat(engine.calls.get()).isEqualTo(before);
        assertThat(reused.content().candidates().stream().map(c -> c.name())).containsExactlyElementsOf(
                first.certificate().candidates().stream().map(c -> c.name()).toList());
        assertThat(count("candidate_reuse_set")).isEqualTo(2); // Reuse cannot refresh original evidence.
        assertThat(count("candidate_reuse_use")).isEqualTo(1);
        assertThat(count("candidate_reuse_review")).isEqualTo(1);
        String payload = jdbc.queryForObject("SELECT certificate::text FROM candidate_reuse_set WHERE id = ?", String.class, first.id());
        assertThat(payload).doesNotContain(input().prompt(), "sourceText", "recentDirectChoices", "\"prompt\"");
        assertThat(payload).doesNotContain("allocationInterpretation", "allocationComparable", "allocationNoSemanticDuplicates", "allocationFeasible", "intentId");
        assertThatThrownBy(() -> jdbc.update("UPDATE candidate_reuse_set SET certificate = '{}'::jsonb WHERE id = ?", first.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void missesRespectExactPromptSizeTimezoneAndFallbackRunsOnce() {
        ready(actor()); approve(onlyPending().id()); engine.calls.set(0);
        for (var request : List.of(new GenerationInput("합성  테스트 요청", 8, "ko-KR", "Asia/Seoul"),
                new GenerationInput(input().prompt(), 16, "ko-KR", "Asia/Seoul"),
                new GenerationInput(input().prompt(), 8, "ko-KR", "UTC"))) ready(actor(), request);
        assertThat(engine.calls.get()).isEqualTo(3); assertThat(count("candidate_reuse_use")).isZero();
    }
    @Test void rejectedRevokedExpiredAndUnavailableSetsFallBackWithoutSuccessFabrication() {
        ready(actor()); var first = onlyPending(); reuse.reject(first.id(), "synthetic-operator", "fixture rejection");
        ready(actor()); assertThat(engine.calls.get()).isEqualTo(2);
        var second = onlyPending(); approve(second.id()); reuse.revoke(second.id(), "synthetic-operator", "fixture revoke");
        ready(actor()); assertThat(engine.calls.get()).isEqualTo(3);
        var third = onlyPending(); approve(third.id());
        var past = Timestamp.from(clock.instant().minusSeconds(60));
        jdbc.update("UPDATE candidate_reuse_set SET approved_at = ?, expires_at = ? WHERE id = ?", past,
                Timestamp.from(clock.instant().minusSeconds(1)), third.id());
        engine.failure = PROVIDER_UNAVAILABLE;
        String actor = actor(); var job = generation.create(actor, actor, input()); worker.runOne();
        assertThat(engine.calls.get()).isEqualTo(4);
        assertThat(generation.job(actor, job.id()).error()).isEqualTo(PROVIDER_UNAVAILABLE);
        assertThat(count("candidate_reuse_use")).isZero();
    }
    @Test void historyAffectedRequestsNeitherReuseNorStage() {
        String actor = actor(); var draft = ready(actor); approve(onlyPending().id());
        var start = tournaments.start(actor, draft.id(), 1);
        tournaments.select(actor, start.sessionId(), PlaySessionTest.complete(start.snapshot(), false).subList(0, 1));
        ready(actor);
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(engine.lastContext.recentDirectChoices()).hasSize(1);
        assertThat(count("candidate_reuse_set")).isEqualTo(1); assertThat(count("candidate_reuse_use")).isZero();
    }
    @Test void identicalMembershipRegenerationMissesPoolAndRejectsIdenticalFallbackPreservingDraft() {
        String actor = actor(); var original = ready(actor); approve(onlyPending().id());
        // Fixture provider changes IDs for the new job but preserves all names; IDs/order are not novelty.
        var job = generation.regenerate(actor, actor, original.id(), 1); worker.runOne();
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(generation.job(actor, job.id()).error()).isEqualTo(QUALITY_GATE_FAILED);
        assertThat(generation.preview(actor, original.id())).isEqualTo(original);
        assertThat(count("candidate_reuse_set")).isEqualTo(1); assertThat(count("candidate_reuse_use")).isZero();
    }
    @Test void renamedFallbackWithSameOriginalCoreActivitiesCannotConsumeRegeneration() {
        String actor = actor(); var original = ready(actor); approve(onlyPending().id());
        engine.names = "renamed"; engine.activities = "A";
        var job = generation.regenerate(actor, actor, original.id(), 1); worker.runOne();
        assertThat(engine.calls.get()).isEqualTo(2);
        assertThat(generation.job(actor, job.id()).error()).isEqualTo(QUALITY_GATE_FAILED);
        assertThat(generation.preview(actor, original.id())).isEqualTo(original);
    }
    @Test void distinctApprovedSetCanReplaceOriginalOnceWithNoEngineCall() {
        String actor = actor(); var original = ready(actor); approve(onlyPending().id());
        // A different exact complete set can originate only from another successful engine result.
        engine.names = "B";
        var newSetInput = input();
        var now = clock.instant(); var generated = ReuseFixtures.generated(newSetInput, now, "B", "b");
        var staged = new StoredSet(UUID.randomUUID().toString(), ReusePolicy.contextHash(input()),
                ReusePolicy.membershipHash(generated.candidates().candidates().stream().map(c -> c.name()).toList()),
                ReusePolicy.VERSION, "synthetic-approved-source", 1, State.PENDING, generated.publicTitle(), generated.providerVersion(),
                generated.validatorVersion(), generated.certificate(), now, null, false, false, false);
        repository.stage(staged); approve(staged.id()); engine.calls.set(0);
        var job = generation.regenerate(actor, actor, original.id(), 1); worker.runOne();
        var updated = generation.preview(actor, original.id());
        assertThat(generation.job(actor, job.id()).state()).isEqualTo("READY");
        assertThat(engine.calls.get()).isZero(); assertThat(updated.version()).isEqualTo(2); assertThat(updated.regenerationUsed()).isEqualTo(1);
        assertThat(updated.content().candidates().stream().map(c -> c.name())).allMatch(name -> name.startsWith("B"));
    }
    @Test void fencedStaleAttemptCannotStageOrRecordUsageAndCurrentAttemptStagesOnce() {
        String actor = actor(); var job = generation.create(actor, actor, input());
        engine.during = () -> {
            jdbc.update("UPDATE generation_job SET lease_until = ? WHERE id = ?", Timestamp.from(clock.instant().minusSeconds(1)), job.id());
            worker.recoverExpired();
        };
        worker.runOne();
        assertThat(generation.job(actor, job.id()).state()).isEqualTo("QUEUED");
        assertThat(count("candidate_reuse_set")).isZero(); assertThat(count("draft")).isZero();
        worker.runOne();
        assertThat(generation.job(actor, job.id()).state()).isEqualTo("READY");
        assertThat(onlyPending().sourceAttempt()).isEqualTo(2); assertThat(count("candidate_reuse_set")).isEqualTo(1);
    }
    @Test void approvalRevokedBetweenLookupAndCompletionCannotPublishAHit() {
        ready(actor()); var set = onlyPending(); approve(set.id());
        var hit = reuse.find(input(), ReuseFixtures.context(), null).orElseThrow();
        reuse.revoke(set.id(), "synthetic-operator", "fixture revoke");
        assertThatThrownBy(() -> reuse.requireCurrentApproval(hit)).isInstanceOf(Failure.class);
    }
    @Test void reuseAdmissionsStillConsumeDailyQuotaAndIdempotentRetryDoesNotConsumeAgain() {
        ready(actor()); approve(onlyPending().id()); engine.calls.set(0);
        String actor = actor();
        java.util.function.Supplier<GenerationRepository.Job> submit = () -> idempotency.execute(actor, "/generation-jobs", "same-key", input(),
                GenerationRepository.Job.class, () -> generation.create(actor, actor, input()));
        var first = submit.get(); assertThat(submit.get()).isEqualTo(first); worker.runOne();
        ready(actor);
        assertThatThrownBy(() -> generation.create(actor, actor, input())).isInstanceOfSatisfying(Failure.class,
                failure -> assertThat(failure.code()).isEqualTo(RATE_LIMITED));
        assertThat(engine.calls.get()).isZero(); assertThat(count("candidate_reuse_use")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job WHERE actor_id = ?", Integer.class, actor)).isEqualTo(2);
    }
    @Test void sourceJobRetentionDoesNotRemoveApprovedSetsOrChangeFrozenSharedSnapshot() {
        ready(actor()); var set = onlyPending(); approve(set.id());
        String actor = actor(); var draft = ready(actor); var start = tournaments.start(actor, draft.id(), 1);
        tournaments.select(actor, start.sessionId(), PlaySessionTest.complete(start.snapshot(), false));
        var link = sharing.create(actor, start.sessionId());
        var old = Timestamp.from(clock.instant().minusSeconds(2 * 86400L));
        jdbc.update("UPDATE generation_job SET created_at = ?", old); jdbc.update("UPDATE draft SET created_at = ?", old);
        retention.clean();
        assertThat(count("generation_job")).isZero(); assertThat(count("draft")).isZero(); assertThat(count("candidate_reuse_set")).isEqualTo(1);
        reuse.revoke(set.id(), "synthetic-operator", "fixture revoke");
        assertThat(sharing.read(link.token()).snapshot()).isEqualTo(start.snapshot());
        assertThat(sharing.replay(actor(), link.token()).snapshot()).isEqualTo(start.snapshot());
        assertThat(count("candidate_reuse_use")).isEqualTo(1);
        jdbc.update("UPDATE candidate_reuse_set SET updated_at = ?", Timestamp.from(clock.instant().minusSeconds(8 * 86400L)));
        retention.clean(); assertThat(count("candidate_reuse_set")).isZero();
        assertThat(count("candidate_reuse_review")).isEqualTo(2); // Bounded audit survives shorter payload retention.
    }
    @Test void legacyDevelopmentOrTestEngineWithoutCertificateNeverStages() {
        engine.certified = false; ready(actor());
        assertThat(count("candidate_reuse_set")).isZero(); assertThat(count("candidate_reuse_use")).isZero();
    }
    @Test void literalOldApprovedCertificateIsAMissAndFallsBackOnceWithoutRewritingEvidence() {
        String id = legacySet(State.APPROVED, "synthetic-old-approved-job");
        String original = certificateJson(id);
        assertThat(original).contains("allocationInterpretation", "allocationComparable", "allocationNoSemanticDuplicates", "allocationFeasible", "intentId");
        assertThat(repository.get(id, false).orElseThrow().certificate()).isNull();
        assertThat(reuse.find(input(), ReuseFixtures.context(), null)).isEmpty();
        ready(actor());
        assertThat(engine.calls.get()).isEqualTo(1);
        assertThat(count("candidate_reuse_use")).isZero();
        assertThat(certificateJson(id)).isEqualTo(original);
        assertThat(repository.get(id, false).orElseThrow().policyVersion()).isEqualTo("approved-complete-set-v3-engine-v22");
    }
    @Test void literalOldPendingCertificateCannotBeApprovedOrSilentlyUpgraded() {
        String id = legacySet(State.PENDING, "synthetic-old-pending-job");
        String original = certificateJson(id);
        assertThat(reuse.pending()).singleElement().satisfies(set -> assertThat(set.certificate()).isNull());
        assertThatThrownBy(() -> approve(id)).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.get(id, false).orElseThrow().state()).isEqualTo(State.PENDING);
        assertThat(count("candidate_reuse_review")).isZero();
        assertThat(certificateJson(id)).isEqualTo(original);
        assertThat(engine.calls.get()).isZero();
    }
    @Test void oldDraftCertificateHasNoTrustedCoreHashButFrozenShareRemainsPlayable() {
        engine.certified = false;
        String actor = actor();
        var draft = ready(actor);
        String sourceJob = jdbc.queryForObject("SELECT id FROM generation_job WHERE draft_id = ?", String.class, draft.id());
        String id = legacySet(State.APPROVED, sourceJob);
        String original = certificateJson(id);
        String membership = ReusePolicy.membershipHash(draft.content().candidates().stream().map(c -> c.name()).toList());
        assertThat(repository.forDraft(actor, draft.id()).orElseThrow().certificate()).isNull();
        assertThat(reuse.previousCoreActivities(actor, draft.id(), membership)).isEmpty();
        var start = tournaments.start(actor, draft.id(), 1);
        tournaments.select(actor, start.sessionId(), PlaySessionTest.complete(start.snapshot(), false));
        var share = sharing.create(actor, start.sessionId());
        assertThat(sharing.read(share.token()).snapshot()).isEqualTo(start.snapshot());
        var replay = sharing.replay(actor(), share.token());
        assertThat(replay.snapshot()).isEqualTo(start.snapshot());
        assertThat(replay.sessionId()).isNotEqualTo(start.sessionId());
        assertThat(certificateJson(id)).isEqualTo(original);
        assertThat(engine.calls.get()).isEqualTo(1);
        assertThat(count("candidate_reuse_use")).isZero();
    }

    @TestConfiguration static class FixtureConfiguration {
        @Bean @Primary FixtureEngine fixtureEngine(Clock clock) { return new FixtureEngine(clock); }
    }
    static final class FixtureEngine implements CandidateEngine {
        final AtomicInteger calls = new AtomicInteger(); final Clock clock;
        String names = "A", activities; boolean certified = true; Failure.Code failure; Runnable during; Context lastContext;
        FixtureEngine(Clock clock) { this.clock = clock; }
        @Override public Generated generate(GenerationInput input, Context context) {
            calls.incrementAndGet(); lastContext = context;
            if (failure != null) throw Failure.of(failure);
            if (during != null) { var action = during; during = null; action.run(); }
            var result = ReuseFixtures.generated(input, clock.instant(), names, context.jobId(), activities == null ? names : activities);
            return certified ? result : new Generated(result.candidates(), result.publicTitle(), result.providerVersion(), result.validatorVersion());
        }
        void reset() { calls.set(0); names = "A"; activities = null; certified = true; failure = null; during = null; lastContext = null; }
    }
}
