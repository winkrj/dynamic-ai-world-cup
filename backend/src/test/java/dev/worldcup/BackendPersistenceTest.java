package dev.worldcup;

import static org.assertj.core.api.Assertions.*;
import static dev.worldcup.shared.Failure.Code.*;
import dev.worldcup.generation.*;
import dev.worldcup.identity.ActorService;
import dev.worldcup.infrastructure.IdempotencyService;
import dev.worldcup.infrastructure.RetentionService;
import dev.worldcup.sharing.SharingService;
import dev.worldcup.shared.Failure;
import dev.worldcup.support.*;
import dev.worldcup.tournament.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@Import(EngineTestConfiguration.class)
class BackendPersistenceTest extends PostgresSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired ActorService actors;
    @Autowired GenerationService generation;
    @Autowired GenerationRepository jobs;
    @Autowired GenerationWorker worker;
    @Autowired TournamentService tournaments;
    @Autowired TournamentRepository sessions;
    @Autowired SharingService sharing;
    @Autowired IdempotencyService idempotency;
    @Autowired RetentionService retention;
    @Autowired ControlledEngine engine;
    @Autowired TransactionTemplate transaction;

    @BeforeEach void reset() {
        // This database belongs exclusively to PostgresSupport's disposable Testcontainer.
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota CASCADE");
        engine.reset();
    }
    private GenerationInput input() { return new GenerationInput("개인 고민: 로그나 공유에 넣지 않기", 8, "ko-KR", "Asia/Seoul"); }
    private String actor() { return actors.issue().actorId(); }
    private Draft ready(String actor) {
        var job = generation.create(actor, "127.0.0.1", input());
        assertThat(worker.runOne()).isTrue();
        return generation.preview(actor, generation.job(actor, job.id()).draftId());
    }
    private void fails(Failure.Code code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(Failure.class, f -> assertThat(f.code()).isEqualTo(code));
    }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.call(); });
            var b = executor.submit(() -> { start.await(); return second.call(); });
            start.countDown();
            return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
        }
    }
    private Object outcome(Callable<?> action) throws Exception {
        try { return action.call(); } catch (Failure failure) { return failure.code(); }
    }
    @Test void regenerationFailurePreservesEverythingAndOnlySuccessfulReplacementConsumesOne() {
        String actor = actor(); var original = ready(actor);
        engine.failNext = QUALITY_GATE_FAILED;
        var failed = generation.regenerate(actor, "127.0.0.1", original.id(), 1);
        fails(OPERATION_IN_PROGRESS, () -> generation.preview(actor, original.id()));
        worker.runOne();
        assertThat(generation.job(actor, failed.id()).error()).isEqualTo(QUALITY_GATE_FAILED);
        assertThat(generation.preview(actor, original.id())).isEqualTo(original);
        var replacement = generation.regenerate(actor, "127.0.0.1", original.id(), 1);
        worker.runOne();
        var updated = generation.preview(actor, original.id());
        assertThat(generation.job(actor, replacement.id()).state()).isEqualTo("READY");
        assertThat(updated.version()).isEqualTo(2);
        assertThat(updated.regenerationUsed()).isEqualTo(1);
        assertThat(updated.content().candidates()).isNotEqualTo(original.content().candidates());
        fails(REGENERATION_EXHAUSTED, () -> generation.regenerate(actor, "127.0.0.1", original.id(), 2));
        fails(VERSION_CONFLICT, () -> tournaments.start(actor, original.id(), 1));
    }
    @Test void concurrentRegenerationReservesOneJob() throws Exception {
        String actor = actor(); var draft = ready(actor);
        var outcomes = race(() -> outcome(() -> generation.regenerate(actor, "127.0.0.1", draft.id(), 1)),
                () -> outcome(() -> generation.regenerate(actor, "127.0.0.1", draft.id(), 1)));
        assertThat(outcomes.stream().filter(GenerationRepository.Job.class::isInstance)).hasSize(1);
        assertThat(outcomes).contains(OPERATION_IN_PROGRESS);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job WHERE draft_id = ? AND state = 'QUEUED'", Integer.class, draft.id())).isEqualTo(1);
    }
    @Test void concurrentStartConvergesToOneSnapshotAndOriginalSession() throws Exception {
        String actor = actor(); var draft = ready(actor);
        var outcomes = race(() -> tournaments.start(actor, draft.id(), 1), () -> tournaments.start(actor, draft.id(), 1));
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bracket_snapshot", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM play_session", Integer.class)).isEqualTo(1);
        fails(ALREADY_FROZEN, () -> generation.regenerate(actor, "127.0.0.1", draft.id(), 1));
    }
    @Test void startAndRegenerationCannotBothWin() throws Exception {
        String actor = actor(); var draft = ready(actor);
        var outcomes = race(() -> outcome(() -> tournaments.start(actor, draft.id(), 1)),
                () -> outcome(() -> generation.regenerate(actor, "127.0.0.1", draft.id(), 1)));
        assertThat(outcomes.stream().filter(o -> !(o instanceof Failure.Code))).hasSize(1);
        assertThat(outcomes.stream().filter(Failure.Code.class::isInstance)).hasSize(1);
    }
    @Test void concurrentIdempotencyRunsMutationOnceAndRejectsDifferentBody() throws Exception {
        String actor = actor(); var calls = new AtomicInteger();
        Callable<GenerationRepository.Job> submit = () -> idempotency.execute(actor, "/generation-jobs", "same-key", input(), GenerationRepository.Job.class,
                () -> { calls.incrementAndGet(); return generation.create(actor, "127.0.0.1", input()); });
        var outcomes = race(submit, submit);
        assertThat(outcomes.get(0)).isEqualTo(outcomes.get(1));
        assertThat(calls.get()).isEqualTo(1);
        fails(IDEMPOTENCY_CONFLICT, () -> idempotency.execute(actor, "/generation-jobs", "same-key", "different", GenerationRepository.Job.class, () -> null));
        idempotency.execute(actor, "/another-resource", "same-key", "different", String.class, () -> "separate");
    }
    @Test void failureRollsBackIdempotencyReservationAndQuota() {
        String actor = actor();
        fails(NOT_FOUND, () -> idempotency.execute(actor, "/regenerate/missing", "key", input(), String.class, () -> {
            generation.regenerate(actor, "127.0.0.1", "missing", 1); return "never";
        }));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_request", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_rate_event", Integer.class)).isZero();
    }
    @Test void snapshotsRejectSqlUpdatesAndDeletesAndStayIndependentOfDrafts() {
        String actor = actor(); var draft = ready(actor); var started = tournaments.start(actor, draft.id(), 1);
        assertThatThrownBy(() -> jdbc.update("UPDATE bracket_snapshot SET payload = '{}'::jsonb WHERE id = ?", started.snapshot().snapshotId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM bracket_snapshot WHERE id = ?", started.snapshot().snapshotId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("UPDATE draft SET content = '{}'::jsonb WHERE id = ?", draft.id());
        assertThat(tournaments.snapshot(actor, started.snapshot().snapshotId())).isEqualTo(started.snapshot());
        fails(NOT_FOUND, () -> tournaments.snapshot(actor(), started.snapshot().snapshotId()));
    }
    @Test void atomicBatchRejectsInvalidSuffixAndConflictingConcurrentDecision() throws Exception {
        String actor = actor(); var draft = ready(actor); var start = tournaments.start(actor, draft.id(), 1);
        var events = PlaySessionTest.complete(start.snapshot(), false);
        var invalid = new Selection("bad", 1, "not-in-pair", Selection.Reason.USER_SELECTED, 1);
        fails(INVALID_SELECTION, () -> tournaments.select(actor, start.sessionId(), List.of(events.getFirst(), invalid)));
        assertThat(sessions.selections(start.sessionId())).isEmpty();
        var alternate = new Selection("alternate", 0, start.snapshot().initialOrder().get(1), Selection.Reason.USER_SELECTED, 1);
        var outcomes = race(() -> outcome(() -> tournaments.select(actor, start.sessionId(), List.of(events.getFirst()))),
                () -> outcome(() -> tournaments.select(actor, start.sessionId(), List.of(alternate))));
        assertThat(outcomes.stream().filter(PlaySession.Result.class::isInstance)).hasSize(1);
        assertThat(sessions.selections(start.sessionId())).hasSize(1);
    }
    @Test void sameShareReplaysWithoutEngineAndRetainsCreatorChampion() {
        String actor = actor(); var draft = ready(actor); var start = tournaments.start(actor, draft.id(), 1);
        fails(SESSION_NOT_COMPLETED, () -> sharing.create(actor, start.sessionId()));
        var events = PlaySessionTest.complete(start.snapshot(), false);
        tournaments.select(actor, start.sessionId(), events.subList(0, 2));
        var completed = tournaments.select(actor, start.sessionId(), events);
        tournaments.select(actor, start.sessionId(), events);
        var link = sharing.create(actor, start.sessionId());
        assertThat(sharing.create(actor, start.sessionId())).isEqualTo(link);
        int calls = engine.calls.get();
        String replayer = actor(); var replay = sharing.replay(replayer, link.token());
        assertThat(replay.snapshot()).isEqualTo(start.snapshot());
        assertThat(replay.sessionId()).isNotEqualTo(start.sessionId());
        var other = tournaments.select(replayer, replay.sessionId(), PlaySessionTest.complete(replay.snapshot(), true));
        assertThat(other.championId()).isNotEqualTo(completed.championId());
        assertThat(sharing.read(link.token()).championId()).isEqualTo(completed.championId());
        assertThat(engine.calls.get()).isEqualTo(calls);
        assertThat(tournaments.snapshot(replayer, start.snapshot().snapshotId())).isEqualTo(start.snapshot());
        assertThat(sessions.recentDirectChoices(actor, Instant.EPOCH)).hasSize(4);
        var next = generation.create(actor, "127.0.0.1", input()); worker.runOne();
        assertThat(engine.lastContext.recentDirectChoices()).hasSize(4);
        assertThat(generation.job(actor, next.id()).state()).isEqualTo("READY");
    }
    @Test void workerFencesStaleAttemptAfterLeaseRecovery() {
        String actor = actor(); generation.create(actor, "127.0.0.1", input());
        Instant now = Instant.now();
        var first = transaction.execute(status -> jobs.claim(now).orElseThrow());
        Instant expired = now.plusSeconds(61);
        transaction.executeWithoutResult(status -> jobs.expired(expired).forEach(job -> jobs.recover(job, expired)));
        var second = transaction.execute(status -> jobs.claim(expired).orElseThrow());
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.attempt()).isEqualTo(2);
        var generated = engine.generate(input(), new CandidateEngine.Context(first.id(), 1, first.leaseUntil(), List.of()));
        var content = DraftContent.from(generated, new SecureRandom());
        Boolean staleAccepted = transaction.execute(status -> jobs.complete(first, content, generated, expired));
        Boolean currentAccepted = transaction.execute(status -> jobs.complete(second, content, generated, expired));
        assertThat(staleAccepted).isFalse();
        assertThat(currentAccepted).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM draft", Integer.class)).isEqualTo(1);
    }
    @Test void exhaustedLeaseRecoveryRestoresRegenerationWithoutSpendingIt() {
        String actor = actor(); var draft = ready(actor);
        var job = generation.regenerate(actor, "127.0.0.1", draft.id(), 1);
        Instant now = Instant.now();
        transaction.execute(status -> jobs.claim(now).orElseThrow());
        for (int attempt = 1; attempt <= 2; attempt++) {
            Instant expired = now.plusSeconds(attempt * 61L);
            transaction.executeWithoutResult(status -> jobs.expired(expired).forEach(j -> jobs.recover(j, expired)));
            if (attempt == 1) transaction.execute(status -> jobs.claim(expired).orElseThrow());
        }
        assertThat(generation.job(actor, job.id()).state()).isEqualTo("FAILED");
        assertThat(generation.preview(actor, draft.id())).isEqualTo(draft);
    }
    @Test void wrongSizedEngineOutputNeverBecomesPreview() {
        String actor = actor(); engine.wrongSize = true;
        var job = generation.create(actor, "127.0.0.1", input()); worker.runOne();
        assertThat(generation.job(actor, job.id()).error()).isEqualTo(QUALITY_GATE_FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM draft", Integer.class)).isZero();
    }
    @Test void slidingQuotaAppliesAcrossAnonymousActorsAndExpires() {
        String actor = actor();
        for (int i = 0; i < 5; i++) generation.create(actor, "127.0.0.1", input());
        fails(RATE_LIMITED, () -> generation.create(actor, "another-ip", input()));
        fails(RATE_LIMITED, () -> generation.create(actor(), "127.0.0.1", input()));
        jdbc.update("UPDATE generation_rate_event SET created_at = ?", Timestamp.from(Instant.now().minusSeconds(601)));
        assertThat(generation.create(actor, "127.0.0.1", input()).state()).isEqualTo("QUEUED");
    }
    @Test void retentionDeletesPrivateDataButSharedBracketStillWorks() {
        String actor = actor(); var draft = ready(actor); var start = tournaments.start(actor, draft.id(), 1);
        tournaments.select(actor, start.sessionId(), PlaySessionTest.complete(start.snapshot(), false));
        var link = sharing.create(actor, start.sessionId());
        var old = Timestamp.from(Instant.now().minusSeconds(35 * 86400L));
        jdbc.update("UPDATE draft SET created_at = ?", old);
        jdbc.update("UPDATE generation_job SET created_at = ?", old);
        jdbc.update("UPDATE play_session SET created_at = ?", old);
        retention.clean();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM draft", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class)).isZero();
        assertThat(sessions.recentDirectChoices(actor, Instant.EPOCH)).isEmpty();
        assertThat(sharing.read(link.token()).snapshot()).isEqualTo(start.snapshot());
        assertThat(sharing.replay(actor(), link.token()).snapshot()).isEqualTo(start.snapshot());
    }
}
