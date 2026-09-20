package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;

import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.identity.ActorService;
import dev.worldcup.infrastructure.GenerationRateLimit;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.infrastructure.RetentionService;
import dev.worldcup.shared.Failure;
import dev.worldcup.support.ControlledEngine;
import dev.worldcup.support.EngineTestConfiguration;
import dev.worldcup.support.PostgresSupport;
import dev.worldcup.tournament.BracketSnapshot;
import dev.worldcup.tournament.PlaySessionTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.JsonNode;

/** Exercise the production default of two jobs with real HTTP, transactions and PostgreSQL locks. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({EngineTestConfiguration.class, DailyGenerationLimitTest.TimeConfiguration.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DailyGenerationLimitTest extends PostgresSupport {
    private static final Instant NOON_SEOUL = Instant.parse("2026-09-18T03:00:00Z");
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @Autowired GenerationWorker worker;
    @Autowired ControlledEngine engine;
    @Autowired ActorService actors;
    @Autowired RetentionService retention;
    @Autowired MutableClock clock;
    private HttpClient http;

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock quotaClock() { return new MutableClock(NOON_SEOUL); }
    }
    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> value;
        private final ZoneId zone;
        MutableClock(Instant value) { this(new AtomicReference<>(value), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> value, ZoneId zone) { this.value = value; this.zone = zone; }
        void set(Instant instant) { value.set(instant); }
        @Override public Instant instant() { return value.get(); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(value, zone); }
    }

    @BeforeAll void open() { http = HttpClient.newHttpClient(); }
    @AfterAll void close() { http.close(); }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota CASCADE");
        engine.reset();
        clock.set(NOON_SEOUL);
    }
    private Map<String, Object> input(int size, String timezone) {
        return Map.of("prompt", "혼자 할 취미", "size", size, "locale", "ko-KR", "timezone", timezone);
    }
    private Map<String, Object> input() { return input(8, "Asia/Seoul"); }
    private final class Browser {
        volatile String cookie;
        HttpResponse<String> request(String method, String path, Object body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(10));
            if (cookie != null) request.header("Cookie", cookie);
            if (body != null) request.header("Content-Type", "application/json");
            headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Set-Cookie").ifPresent(value -> cookie = value.split(";", 2)[0]);
            return response;
        }
        HttpResponse<String> post(String path, Object body, String key) throws Exception {
            return request("POST", path, body, Map.of("Idempotency-Key", key, "Origin", "https://worldcup.example"));
        }
        HttpResponse<String> post(String path, Object body) throws Exception { return post(path, body, UUID.randomUUID().toString()); }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, Map.of()); }
        String actorScope() { return "actor:" + actors.find(cookie.substring("worldcup_actor=".length())).orElseThrow(); }
    }
    private JsonNode accepted(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
        return json.read(response.body(), JsonNode.class);
    }
    private void rejected(HttpResponse<String> response, int status, String code) {
        assertThat(accepted(response, status).get("code").asString()).isEqualTo(code);
    }
    private void limited(HttpResponse<String> response, long retrySeconds) {
        rejected(response, 429, "RATE_LIMITED");
        assertThat(response.headers().firstValue("Retry-After")).contains(Long.toString(retrySeconds));
    }
    private int actorEvents(Browser browser) {
        return jdbc.queryForObject("SELECT count(*) FROM generation_rate_event WHERE scope = ?", Integer.class, browser.actorScope());
    }
    private int jobs() { return jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class); }
    private String ready(Browser browser) throws Exception {
        var queued = accepted(browser.post("/generation-jobs", input()), 202);
        assertThat(worker.runOne()).isTrue();
        var finished = accepted(browser.get("/generation-jobs/" + queued.get("jobId").asString()), 200);
        assertThat(finished.get("status").asString()).isEqualTo("READY");
        return finished.get("draftId").asString();
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

    @Test void defaultTwoCountsEachSizeOnceAndSameIdempotencyKeyStillWorksAfterExhaustion() throws Exception {
        var browser = new Browser();
        String key = UUID.randomUUID().toString();
        var first = accepted(browser.post("/generation-jobs", input(), key), 202);
        accepted(browser.post("/generation-jobs", input(32, "Asia/Seoul")), 202);
        limited(browser.post("/generation-jobs", input(16, "Asia/Seoul")), 12 * 60 * 60);
        assertThat(accepted(browser.post("/generation-jobs", input(), key), 202)).isEqualTo(first);
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jobs()).isEqualTo(2);
        assertThat(engine.calls.get()).isZero();
    }

    @Test void failedAcceptedRegenerationConsumesDailySlotButDoesNotConsumeSuccessfulReplacement() throws Exception {
        var browser = new Browser();
        String draft = ready(browser);
        var original = accepted(browser.get("/drafts/" + draft), 200);
        String route = "/drafts/" + draft + "/regenerations";
        String key = UUID.randomUUID().toString();
        var replacement = Map.of("expectedVersion", 1);
        var queued = accepted(browser.post(route, replacement, key), 202);
        engine.failNext = Failure.Code.QUALITY_GATE_FAILED;
        assertThat(worker.runOne()).isTrue();
        var failed = accepted(browser.get("/generation-jobs/" + queued.get("jobId").asString()), 200);
        assertThat(failed.get("status").asString()).isEqualTo("FAILED");
        assertThat(failed.get("error").get("code").asString()).isEqualTo("QUALITY_GATE_FAILED");
        assertThat(accepted(browser.get("/drafts/" + draft), 200)).isEqualTo(original);
        assertThat(original.get("regenerationsRemaining").asInt()).isEqualTo(1);
        assertThat(accepted(browser.post(route, replacement, key), 202)).isEqualTo(queued);
        limited(browser.post(route, replacement), 12 * 60 * 60);
        limited(browser.post("/generation-jobs", input()), 12 * 60 * 60);
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jobs()).isEqualTo(2);
        assertThat(engine.calls.get()).isEqualTo(2);
    }

    @Test void invalidAdmissionRollsBackBothQuotaScopesAndAllowsTheSameKeyToBeCorrected() throws Exception {
        var browser = new Browser();
        String draft = ready(browser);
        String route = "/drafts/" + draft + "/regenerations";
        String key = UUID.randomUUID().toString();
        rejected(browser.post(route, Map.of("expectedVersion", 99), key), 409, "VERSION_CONFLICT");
        rejected(browser.post("/drafts/missing/regenerations", Map.of("expectedVersion", 1)), 404, "NOT_FOUND");
        rejected(browser.post("/generation-jobs", Map.of("size", 8)), 400, "INVALID_INPUT");
        assertThat(actorEvents(browser)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_rate_event", Integer.class)).isEqualTo(2);
        accepted(browser.post(route, Map.of("expectedVersion", 1), key), 202);
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_rate_event", Integer.class)).isEqualTo(4);
        assertThat(jobs()).isEqualTo(2);
    }

    @Test void distinctConcurrentRequestsCannotBothTakeTheLastDailySlot() throws Exception {
        var browser = new Browser();
        accepted(browser.post("/generation-jobs", input()), 202);
        var results = race(() -> browser.post("/generation-jobs", input()), () -> browser.post("/generation-jobs", input()));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(202, 429);
        limited(results.stream().filter(response -> response.statusCode() == 429).findFirst().orElseThrow(), 12 * 60 * 60);
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jobs()).isEqualTo(2);
    }

    @Test void concurrentIdenticalRetriesReserveOnlyOneLastSlotAndReturnTheSameJob() throws Exception {
        var browser = new Browser();
        accepted(browser.post("/generation-jobs", input()), 202);
        String key = UUID.randomUUID().toString();
        var results = race(() -> browser.post("/generation-jobs", input(), key), () -> browser.post("/generation-jobs", input(), key));
        assertThat(accepted(results.getFirst(), 202)).isEqualTo(accepted(results.getLast(), 202));
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jobs()).isEqualTo(2);
    }

    @Test void resetsAtSeoulMidnightNotClientTimezoneOrUtcAndRoundsRetryDelayUp() throws Exception {
        clock.set(Instant.parse("2026-09-18T14:59:59.250Z"));
        var browser = new Browser();
        accepted(browser.post("/generation-jobs", input()), 202);
        accepted(browser.post("/generation-jobs", input()), 202);
        limited(browser.post("/generation-jobs", input(8, "America/Los_Angeles")), 1);
        clock.set(Instant.parse("2026-09-18T15:00:00Z"));
        accepted(browser.post("/generation-jobs", input(8, "America/Los_Angeles")), 202);
        accepted(browser.post("/generation-jobs", input(8, "UTC")), 202);
        limited(browser.post("/generation-jobs", input()), 24 * 60 * 60);
        assertThat(actorEvents(browser)).isEqualTo(4);
        assertThat(jobs()).isEqualTo(4);
    }

    @Test void lockWaitCrossingMidnightAccountsAgainstTheNewDay() throws Exception {
        clock.set(Instant.parse("2026-09-18T14:59:59Z"));
        var browser = new Browser();
        accepted(browser.post("/generation-jobs", input()), 202);
        accepted(browser.post("/generation-jobs", input()), 202);
        try (var connection = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT scope FROM generation_quota WHERE scope = ? FOR UPDATE")) {
                lock.setString(1, browser.actorScope());
                lock.executeQuery().close();
            }
            var pending = executor.submit(() -> browser.post("/generation-jobs", input()));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean waiting = false;
                while (System.nanoTime() < deadline) {
                    waiting = jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity
                            WHERE wait_event_type = 'Lock' AND query LIKE '%generation_quota%')
                            """, Boolean.class);
                    if (waiting) break;
                    Thread.sleep(10);
                }
                assertThat(waiting).as("HTTP admission waited on the actor quota lock").isTrue();
                clock.set(Instant.parse("2026-09-18T15:00:00Z"));
            } finally { connection.commit(); }
            accepted(pending.get(10, TimeUnit.SECONDS), 202);
        }
        assertThat(jdbc.queryForObject("SELECT max(created_at) FROM generation_rate_event WHERE scope = ?",
                Timestamp.class, browser.actorScope()).toInstant()).isEqualTo(clock.instant());
        assertThat(actorEvents(browser)).isEqualTo(3);
    }

    @Test void retentionKeepsDailyEvidenceAfterBurstWindowAndRemovesExpiredScopes() throws Exception {
        var browser = new Browser();
        accepted(browser.post("/generation-jobs", input()), 202);
        accepted(browser.post("/generation-jobs", input()), 202);
        var stale = Timestamp.from(NOON_SEOUL.minus(Duration.ofHours(25)));
        jdbc.update("INSERT INTO generation_quota(scope, last_used_at) VALUES ('actor:stale', ?)", stale);
        jdbc.update("INSERT INTO generation_rate_event(scope, created_at) VALUES ('actor:stale', ?)", stale);
        clock.set(NOON_SEOUL.plus(Duration.ofMinutes(11)));
        retention.clean();
        limited(browser.post("/generation-jobs", input()), 12 * 60 * 60 - 11 * 60);
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_quota WHERE scope = 'actor:stale'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_rate_event", Integer.class)).isEqualTo(4);
    }

    @Test void freshCookiesAndSpoofedForwardedAddressesStillShareTheFivePerTenMinuteIpLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            accepted(new Browser().request("POST", "/generation-jobs", input(),
                    Map.of("Idempotency-Key", "key", "X-Forwarded-For", "198.51.100." + i)), 202);
        }
        var browser = new Browser();
        limited(browser.post("/generation-jobs", input()), 600);
        clock.set(NOON_SEOUL.plusMillis(599_250));
        limited(browser.post("/generation-jobs", input()), 1);
        clock.set(NOON_SEOUL.plusSeconds(600));
        accepted(browser.post("/generation-jobs", input()), 202);
        assertThat(actorEvents(browser)).isEqualTo(1);
        assertThat(jobs()).isEqualTo(6);
    }

    @Test void midnightDoesNotShortenAnOverlappingIpBurstRestriction() throws Exception {
        Instant beforeMidnight = Instant.parse("2026-09-18T14:59:59Z");
        clock.set(beforeMidnight);
        var browser = new Browser();
        accepted(browser.post("/generation-jobs", input()), 202);
        accepted(browser.post("/generation-jobs", input()), 202);
        for (int i = 0; i < 3; i++) accepted(new Browser().post("/generation-jobs", input()), 202);
        limited(browser.post("/generation-jobs", input()), 600);
        clock.set(beforeMidnight.plusSeconds(1));
        limited(browser.post("/generation-jobs", input()), 599);
        clock.set(beforeMidnight.plusSeconds(600));
        accepted(browser.post("/generation-jobs", input()), 202);
        assertThat(actorEvents(browser)).isEqualTo(3);
        assertThat(jobs()).isEqualTo(6);
    }

    @Test void exhaustedActorCanStillStartCompleteShareAndReplayWithoutQuotaOrEngineCalls() throws Exception {
        var browser = new Browser();
        String draft = ready(browser);
        accepted(browser.post("/generation-jobs", input()), 202);
        limited(browser.post("/generation-jobs", input()), 12 * 60 * 60);
        var started = accepted(browser.post("/drafts/" + draft + "/start", Map.of("expectedVersion", 1)), 201);
        var snapshot = json.read(started.get("snapshot").toString(), BracketSnapshot.class);
        String session = started.get("sessionId").asString();
        accepted(browser.post("/sessions/" + session + "/selections", Map.of("events", PlaySessionTest.complete(snapshot, false))), 200);
        var share = accepted(browser.post("/sessions/" + session + "/shares", null), 201);
        String route = "/shares/" + share.get("token").asString();
        assertThat(accepted(browser.get(route), 200).get("snapshot")).isEqualTo(started.get("snapshot"));
        var replay = accepted(browser.post(route + "/sessions", null), 201);
        assertThat(replay.get("sessionId")).isNotEqualTo(started.get("sessionId"));
        assertThat(replay.get("snapshot")).isEqualTo(started.get("snapshot"));
        accepted(browser.post("/sessions/" + replay.get("sessionId").asString() + "/selections",
                Map.of("events", PlaySessionTest.complete(snapshot, true))), 200);
        assertThat(actorEvents(browser)).isEqualTo(2);
        assertThat(jobs()).isEqualTo(2);
        assertThat(engine.calls.get()).isEqualTo(1);
    }

    @Test void unknownRateLimitResetDoesNotInventARetryAfterHeader() {
        var errors = new ApiErrors();
        var request = new MockHttpServletRequest();
        var unknown = errors.business(Failure.of(Failure.Code.RATE_LIMITED), request);
        assertThat(unknown.getStatusCode().value()).isEqualTo(429);
        assertThat(unknown.getHeaders().getFirst("Retry-After")).isNull();
        assertThat(errors.business(Failure.rateLimited(37), request).getHeaders().getFirst("Retry-After")).isEqualTo("37");
        assertThatThrownBy(() -> Failure.rateLimited(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GenerationRateLimit(jdbc, clock, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
