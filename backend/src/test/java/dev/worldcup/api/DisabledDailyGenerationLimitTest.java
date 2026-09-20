package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;

import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.GenerationRepository;
import dev.worldcup.generation.GenerationService;
import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.identity.ActorService;
import dev.worldcup.infrastructure.GenerationRateLimit;
import dev.worldcup.infrastructure.IdempotencyService;
import dev.worldcup.shared.Failure;
import dev.worldcup.support.ControlledEngine;
import dev.worldcup.support.EngineTestConfiguration;
import dev.worldcup.support.PostgresSupport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Zero disables only the daily cap; production admission transactions still enforce burst protection. */
@SpringBootTest(properties = "worldcup.generation.daily-limit=0")
@Import({EngineTestConfiguration.class, DailyGenerationLimitTest.TimeConfiguration.class})
class DisabledDailyGenerationLimitTest extends PostgresSupport {
    private static final Instant NOON_SEOUL = Instant.parse("2026-09-20T03:00:00Z");
    private static final String IP = "192.0.2.1";
    @Autowired JdbcTemplate jdbc;
    @Autowired GenerationService generation;
    @Autowired GenerationWorker worker;
    @Autowired ActorService actors;
    @Autowired IdempotencyService idempotency;
    @Autowired ControlledEngine engine;
    @Autowired TransactionTemplate transaction;
    @Autowired DailyGenerationLimitTest.MutableClock clock;

    @BeforeEach void reset() {
        // PostgresSupport owns this disposable database, never a developer or production database.
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota CASCADE");
        engine.reset();
        clock.set(NOON_SEOUL);
    }
    private GenerationInput input() { return new GenerationInput("합성 심사 횟수 검증", 8, "ko-KR", "Asia/Seoul"); }
    private String actor() { return actors.issue().actorId(); }
    private int events(String scope) {
        return jdbc.queryForObject("SELECT count(*) FROM generation_rate_event WHERE scope = ?", Integer.class, scope);
    }
    private int jobs() { return jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class); }
    private void limited(long retrySeconds, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(Failure.class, failure -> {
            assertThat(failure.code()).isEqualTo(Failure.Code.RATE_LIMITED);
            assertThat(failure.retryAfterSeconds()).hasValue(retrySeconds);
        });
    }
    private GenerationRepository.Job createOnce(String actor, String key) {
        return idempotency.execute(actor, "/generation-jobs", key, input(), GenerationRepository.Job.class,
                () -> generation.create(actor, IP, input()));
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
    private boolean tryCreate(String actor, String ip) {
        try { generation.create(actor, ip, input()); return true; }
        catch (Failure failure) {
            assertThat(failure.code()).isEqualTo(Failure.Code.RATE_LIMITED);
            assertThat(failure.retryAfterSeconds()).hasValue(600);
            return false;
        }
    }

    @Test void fiveJobsExceedTheDefaultDailyCapButRetriesStillConsumeNoAdditionalSlots() {
        String actor = actor();
        var first = createOnce(actor, "first");
        for (int i = 1; i < 5; i++) createOnce(actor, "request-" + i);
        limited(600, () -> createOnce(actor, "sixth"));
        assertThat(createOnce(actor, "first")).isEqualTo(first);
        assertThat(events("actor:" + actor)).isEqualTo(5);
        assertThat(events("ip:" + ActorService.hash(IP))).isEqualTo(5);
        assertThat(jobs()).isEqualTo(5);
        assertThat(engine.calls.get()).isZero();
    }

    @Test void actorBurstRemainsAcrossDifferentIpsAndExpiresAfterTenMinutes() {
        String actor = actor();
        for (int i = 0; i < 5; i++) generation.create(actor, "198.51.100." + i, input());
        limited(600, () -> generation.create(actor, "198.51.100.99", input()));
        clock.set(NOON_SEOUL.plusMillis(599_250));
        limited(1, () -> generation.create(actor, "198.51.100.99", input()));
        clock.set(NOON_SEOUL.plusSeconds(600));
        generation.create(actor, "198.51.100.99", input());
        assertThat(events("actor:" + actor)).isEqualTo(6);
        assertThat(jobs()).isEqualTo(6);
    }

    @Test void freshActorsCannotBypassTheIpBurstRestriction() {
        for (int i = 0; i < 5; i++) generation.create(actor(), IP, input());
        String nextActor = actor();
        limited(600, () -> generation.create(nextActor, IP, input()));
        assertThat(events("actor:" + nextActor)).isZero();
        assertThat(events("ip:" + ActorService.hash(IP))).isEqualTo(5);
        assertThat(jobs()).isEqualTo(5);
    }

    @Test void failedAcceptedJobsStayCountedButInvalidAdmissionRollsBackBothScopes() {
        String actor = actor();
        var initial = generation.create(actor, IP, input());
        assertThat(worker.runOne()).isTrue();
        String draft = generation.job(actor, initial.id()).draftId();
        var original = generation.preview(actor, draft);
        assertThatThrownBy(() -> generation.regenerate(actor, IP, draft, 99))
                .isInstanceOfSatisfying(Failure.class, failure -> assertThat(failure.code()).isEqualTo(Failure.Code.VERSION_CONFLICT));
        assertThat(events("actor:" + actor)).isEqualTo(1);
        assertThat(events("ip:" + ActorService.hash(IP))).isEqualTo(1);
        var failedJob = generation.regenerate(actor, IP, draft, 1);
        engine.failNext = Failure.Code.QUALITY_GATE_FAILED;
        assertThat(worker.runOne()).isTrue();
        assertThat(generation.job(actor, failedJob.id()).state()).isEqualTo("FAILED");
        assertThat(generation.preview(actor, draft)).isEqualTo(original);
        for (int i = 0; i < 3; i++) generation.create(actor, IP, input());
        limited(600, () -> generation.create(actor, IP, input()));
        assertThat(events("actor:" + actor)).isEqualTo(5);
        assertThat(events("ip:" + ActorService.hash(IP))).isEqualTo(5);
        assertThat(jobs()).isEqualTo(5);
    }

    @Test void reenablingDailyProtectionUsesTheRecordedAdmissionsWithoutResettingAccounting() {
        String actor = actor();
        for (int i = 0; i < 7; i++) {
            clock.set(NOON_SEOUL.plus(Duration.ofMinutes(11L * i)));
            generation.create(actor, IP, input());
        }
        clock.set(NOON_SEOUL.plus(Duration.ofMinutes(77)));
        var normalLimit = new GenerationRateLimit(jdbc, clock, 2);
        limited(12 * 60 * 60 - 77 * 60, () -> transaction.executeWithoutResult(status -> normalLimit.reserve(actor, IP)));
        assertThat(events("actor:" + actor)).isEqualTo(7);
        assertThat(events("ip:" + ActorService.hash(IP))).isEqualTo(7);
        assertThat(jobs()).isEqualTo(7);
    }

    @Test void concurrentAdmissionsCannotBothTakeTheLastActorBurstSlot() throws Exception {
        String actor = actor();
        for (int i = 0; i < 4; i++) generation.create(actor, "203.0.113." + i, input());
        var results = race(() -> tryCreate(actor, "203.0.113.100"), () -> tryCreate(actor, "203.0.113.101"));
        assertThat(results).containsExactlyInAnyOrder(true, false);
        assertThat(events("actor:" + actor)).isEqualTo(5);
        assertThat(jobs()).isEqualTo(5);
    }
}
