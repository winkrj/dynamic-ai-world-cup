package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.identity.ActorService;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.shared.Failure;
import dev.worldcup.support.*;
import dev.worldcup.tournament.BracketSnapshot;
import dev.worldcup.tournament.PlaySessionTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "worldcup.generation.daily-limit=100")
@Import(EngineTestConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WorldcupHttpTest extends PostgresSupport {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired GenerationWorker worker;
    @Autowired ControlledEngine engine;
    @Autowired ActorService actors;
    @Autowired JsonCodec json;
    private final List<Map<String, Object>> samples = new ArrayList<>();
    private HttpClient http;
    @BeforeAll void open() { http = HttpClient.newHttpClient(); }
    @AfterAll void close() throws Exception {
        http.close();
        // Actual HTTP responses, consumed by the existing AJV toolchain after Gradle tests.
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/contract-http-samples.json"), json.write(samples));
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota CASCADE");
        engine.reset();
    }
    private Map<String, Object> input(int size) {
        return new LinkedHashMap<>(Map.of("prompt", "PRIVATE-PROMPT: 개인 조건", "size", size, "locale", "ko-KR", "timezone", "Asia/Seoul"));
    }
    private final class Browser {
        String cookie;
        HttpResponse<String> request(String method, String path, String body, Map<String, String> headers) throws Exception {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path));
            if (cookie != null) request.header("Cookie", cookie);
            if (body != null) request.header("Content-Type", "application/json");
            headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Set-Cookie").ifPresent(value -> cookie = value.split(";", 2)[0]);
            return response;
        }
        HttpResponse<String> post(String path, Object body, String key) throws Exception {
            return request("POST", path, body == null ? null : json.write(body), Map.of("Idempotency-Key", key, "Origin", "https://worldcup.example"));
        }
        HttpResponse<String> post(String path, Object body) throws Exception { return post(path, body, UUID.randomUUID().toString()); }
        HttpResponse<String> get(String path) throws Exception { return request("GET", path, null, Map.of()); }
    }
    private JsonNode accepted(HttpResponse<String> response, int status, String schema) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        JsonNode value = json.read(response.body(), JsonNode.class);
        samples.add(Map.of("schema", schema, "value", value));
        return value;
    }
    private void rejected(HttpResponse<String> response, int status, String code) {
        var value = accepted(response, status, "ApiError");
        assertThat(value.get("code").asString()).isEqualTo(code);
        assertThat(response.body()).doesNotContain("PRIVATE-PROMPT", "SQLException", "stackTrace", "password");
    }
    private String ready(Browser browser, int size) throws Exception {
        var queued = accepted(browser.post("/generation-jobs", input(size)), 202, "GenerationJob");
        assertThat(queued.get("draftId").isNull()).isTrue();
        worker.runOne();
        var job = accepted(browser.get("/generation-jobs/" + queued.get("jobId").asString()), 200, "GenerationJob");
        assertThat(job.get("status").asString()).isEqualTo("READY");
        return job.get("draftId").asString();
    }
    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void completeLifecycleMatchesContractAndSharesSameBracket(int size) throws Exception {
        var owner = new Browser(); var outsider = new Browser();
        String draft = ready(owner, size);
        var preview = accepted(owner.get("/drafts/" + draft), 200, "Preview");
        assertThat(preview.get("candidates").size()).isEqualTo(size);
        var started = accepted(owner.post("/drafts/" + draft + "/start", Map.of("expectedVersion", 1)), 201, "SessionStart");
        var retried = accepted(owner.post("/drafts/" + draft + "/start", Map.of("expectedVersion", 1)), 201, "SessionStart");
        assertThat(retried).isEqualTo(started);
        String session = started.get("sessionId").asString();
        var snapshot = json.read(started.get("snapshot").toString(), BracketSnapshot.class);
        assertThat(snapshot.size()).isEqualTo(size);
        assertThat(snapshot.title()).doesNotContain("PRIVATE-PROMPT");
        rejected(outsider.get("/snapshots/" + snapshot.snapshotId()), 404, "NOT_FOUND");
        assertThat(accepted(owner.get("/snapshots/" + snapshot.snapshotId()), 200, "Snapshot")).isEqualTo(started.get("snapshot"));
        var events = PlaySessionTest.complete(snapshot, false);
        String key = UUID.randomUUID().toString();
        var ack = accepted(owner.post("/sessions/" + session + "/selections", Map.of("events", events), key), 200, "SelectionAck");
        assertThat(ack.get("nextSequence").asInt()).isEqualTo(size - 1);
        assertThat(accepted(owner.post("/sessions/" + session + "/selections", Map.of("events", events), key), 200, "SelectionAck")).isEqualTo(ack);
        var share = accepted(owner.post("/sessions/" + session + "/shares", null), 201, "ShareCreated");
        String token = share.get("token").asString();
        assertThat(share.get("url").asString()).isEqualTo("https://worldcup.example/shares/" + token);
        var publicView = accepted(outsider.get("/shares/" + token), 200, "SharedBracket");
        assertThat(publicView.get("snapshot")).isEqualTo(started.get("snapshot"));
        assertThat(publicView.toString()).doesNotContain("PRIVATE-PROMPT", "actor", "evidence", "history");
        int calls = engine.calls.get();
        var replay = accepted(outsider.post("/shares/" + token + "/sessions", null), 201, "SessionStart");
        assertThat(replay.get("snapshot")).isEqualTo(started.get("snapshot"));
        assertThat(replay.get("sessionId")).isNotEqualTo(started.get("sessionId"));
        assertThat(engine.calls.get()).isEqualTo(calls);
    }
    @ParameterizedTest @ValueSource(ints = {8, 16, 32})
    void partialSelectionUploadsKeepCachedAcksAndReplayResultsIndependent(int size) throws Exception {
        var owner = new Browser(); var replayOwner = new Browser();
        String draft = ready(owner, size);
        var started = accepted(owner.post("/drafts/" + draft + "/start", Map.of("expectedVersion", 1)), 201, "SessionStart");
        String session = started.get("sessionId").asString();
        String selections = "/sessions/" + session + "/selections";
        var snapshot = json.read(started.get("snapshot").toString(), BracketSnapshot.class);
        var events = PlaySessionTest.complete(snapshot, false);

        String firstKey = UUID.randomUUID().toString();
        var firstBatch = Map.of("events", events.subList(0, 2));
        var firstAck = accepted(owner.post(selections, firstBatch, firstKey), 200, "SelectionAck");
        assertThat(firstAck.get("nextSequence").asInt()).isEqualTo(2);
        assertThat(firstAck.get("status").asString()).isEqualTo("PLAYING");
        assertThat(firstAck.get("championId").isNull()).isTrue();
        assertThat(accepted(owner.post(selections, firstBatch, firstKey), 200, "SelectionAck")).isEqualTo(firstAck);

        // A restored queue can include an already accepted prefix, but only with a new key for its new body.
        var overlapBatch = Map.of("events", events.subList(1, 4));
        rejected(owner.post(selections, overlapBatch, firstKey), 409, "IDEMPOTENCY_CONFLICT");
        var overlapAck = accepted(owner.post(selections, overlapBatch), 200, "SelectionAck");
        assertThat(overlapAck.get("nextSequence").asInt()).isEqualTo(4);
        assertThat(overlapAck.get("status").asString()).isEqualTo("PLAYING");
        assertThat(accepted(owner.post(selections, firstBatch, firstKey), 200, "SelectionAck")).isEqualTo(firstAck);

        String shareKey = UUID.randomUUID().toString();
        String shares = "/sessions/" + session + "/shares";
        rejected(owner.post(shares, null, shareKey), 409, "SESSION_NOT_COMPLETED");
        var completed = accepted(owner.post(selections, Map.of("events", events.subList(4, events.size()))), 200, "SelectionAck");
        assertThat(completed.get("nextSequence").asInt()).isEqualTo(size - 1);
        assertThat(completed.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(completed.get("championId").asString()).isEqualTo(snapshot.initialOrder().getFirst());
        // Retrying the first upload returns its original ACK, not the latest session state.
        assertThat(accepted(owner.post(selections, firstBatch, firstKey), 200, "SelectionAck")).isEqualTo(firstAck);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pairwise_selection WHERE session_id = ?", Integer.class, session))
                .isEqualTo(size - 1);

        var share = accepted(owner.post(shares, null, shareKey), 201, "ShareCreated");
        assertThat(share.get("championId")).isEqualTo(completed.get("championId"));
        String publicPath = "/shares/" + share.get("token").asString();
        var originalShare = accepted(replayOwner.get(publicPath), 200, "SharedBracket");
        rejected(replayOwner.get("/snapshots/" + snapshot.snapshotId()), 404, "NOT_FOUND");

        int calls = engine.calls.get();
        var replay = accepted(replayOwner.post(publicPath + "/sessions", null), 201, "SessionStart");
        assertThat(replay.get("sessionId")).isNotEqualTo(started.get("sessionId"));
        assertThat(replay.get("snapshot")).isEqualTo(started.get("snapshot"));
        assertThat(accepted(replayOwner.get("/snapshots/" + snapshot.snapshotId()), 200, "Snapshot"))
                .isEqualTo(started.get("snapshot"));
        String replaySession = replay.get("sessionId").asString();
        var replayCompleted = accepted(replayOwner.post("/sessions/" + replaySession + "/selections",
                Map.of("events", PlaySessionTest.complete(snapshot, true))), 200, "SelectionAck");
        assertThat(replayCompleted.get("status").asString()).isEqualTo("COMPLETED");
        assertThat(replayCompleted.get("nextSequence").asInt()).isEqualTo(size - 1);
        assertThat(replayCompleted.get("championId").asString()).isEqualTo(snapshot.initialOrder().getLast());
        assertThat(replayCompleted.get("championId")).isNotEqualTo(completed.get("championId"));
        var replayShare = accepted(replayOwner.post("/sessions/" + replaySession + "/shares", null), 201, "ShareCreated");
        assertThat(replayShare.get("championId")).isEqualTo(replayCompleted.get("championId"));
        assertThat(accepted(owner.get(publicPath), 200, "SharedBracket")).isEqualTo(originalShare);
        assertThat(engine.calls.get()).isEqualTo(calls);
    }
    @Test void issuesHashedAnonymousCookieAndKeepsPrivateResourcesPrivate() throws Exception {
        var browser = new Browser();
        var response = browser.post("/generation-jobs", input(8));
        var job = accepted(response, 202, "GenerationJob");
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/api/v1");
        String token = browser.cookie.substring("worldcup_actor=".length());
        assertThat(jdbc.queryForObject("SELECT token_hash FROM anonymous_actor", String.class)).isEqualTo(ActorService.hash(token)).isNotEqualTo(token);
        var outsider = new Browser(); outsider.cookie = "worldcup_actor=" + actors.issue().token();
        rejected(outsider.get("/generation-jobs/" + job.get("jobId").asString()), 404, "NOT_FOUND");
        worker.runOne();
        String draft = json.read(browser.get("/generation-jobs/" + job.get("jobId").asString()).body(), JsonNode.class).get("draftId").asString();
        rejected(outsider.get("/drafts/" + draft), 404, "NOT_FOUND");
        rejected(outsider.post("/drafts/" + draft + "/start", Map.of("expectedVersion", 1)), 404, "NOT_FOUND");
    }
    @Test void regenerationContractPreservesPreviewOnFailureAndIdempotentRetries() throws Exception {
        var browser = new Browser(); String draft = ready(browser, 8);
        var original = accepted(browser.get("/drafts/" + draft), 200, "Preview");
        engine.failNext = Failure.Code.QUALITY_GATE_FAILED;
        String key = UUID.randomUUID().toString();
        var job = accepted(browser.post("/drafts/" + draft + "/regenerations", Map.of("expectedVersion", 1), key), 202, "GenerationJob");
        rejected(browser.get("/drafts/" + draft), 409, "OPERATION_IN_PROGRESS");
        worker.runOne();
        var failed = accepted(browser.get("/generation-jobs/" + job.get("jobId").asString()), 200, "GenerationJob");
        assertThat(failed.get("error").get("code").asString()).isEqualTo("QUALITY_GATE_FAILED");
        assertThat(accepted(browser.post("/drafts/" + draft + "/regenerations", Map.of("expectedVersion", 1), key), 202, "GenerationJob")).isEqualTo(job);
        assertThat(accepted(browser.get("/drafts/" + draft), 200, "Preview")).isEqualTo(original);
        accepted(browser.post("/drafts/" + draft + "/regenerations", Map.of("expectedVersion", 1)), 202, "GenerationJob");
        worker.runOne();
        var replacement = accepted(browser.get("/drafts/" + draft), 200, "Preview");
        assertThat(replacement.get("version").asInt()).isEqualTo(2);
        assertThat(replacement.get("regenerationsRemaining").asInt()).isZero();
        rejected(browser.post("/drafts/" + draft + "/regenerations", Map.of("expectedVersion", 2)), 409, "REGENERATION_EXHAUSTED");
    }
    @Test void strictInputRejectsUnknownFieldsMissingFieldsNullsCoercionAndInvalidTimezones() throws Exception {
        var browser = new Browser();
        var changes = List.<Map<String, Object>>of(Map.of("size", "8"), Map.of("size", 8.1), Map.of("size", 4), Map.of("timezone", "+09:00"),
                Map.of("locale", "en-US"), Map.of("prompt", "\u00a0\u2003"), Map.of("prompt", "x".repeat(501)), Map.of("unexpected", true));
        for (var change : changes) {
            var body = input(8); body.putAll(change);
            rejected(browser.post("/generation-jobs", body), 400, "INVALID_INPUT");
        }
        for (String field : input(8).keySet()) {
            var missing = input(8); missing.remove(field);
            rejected(browser.post("/generation-jobs", missing), 400, "INVALID_INPUT");
            var nullField = input(8); nullField.put(field, null);
            rejected(browser.post("/generation-jobs", nullField), 400, "INVALID_INPUT");
        }
        rejected(browser.request("POST", "/generation-jobs", "{broken", Map.of("Idempotency-Key", "key")), 400, "INVALID_INPUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class)).isZero();
    }
    @Test void strictSelectionAndVersionFieldsRejectMissingPrimitiveValues() throws Exception {
        var browser = new Browser(); String draft = ready(browser, 8);
        rejected(browser.post("/drafts/" + draft + "/start", Map.of()), 400, "INVALID_INPUT");
        rejected(browser.post("/drafts/" + draft + "/start", Map.of("expectedVersion", "1")), 400, "INVALID_INPUT");
        var started = accepted(browser.post("/drafts/" + draft + "/start", Map.of("expectedVersion", 1)), 201, "SessionStart");
        String session = started.get("sessionId").asString();
        String winner = started.get("snapshot").get("initialOrder").get(0).asString();
        var event = new LinkedHashMap<String, Object>(Map.of("eventId", "e", "sequence", 0, "winnerId", winner, "reason", "USER_SELECTED", "elapsedMs", 6999));
        for (String field : List.copyOf(event.keySet())) {
            var incomplete = new LinkedHashMap<>(event); incomplete.remove(field);
            rejected(browser.post("/sessions/" + session + "/selections", Map.of("events", List.of(incomplete))), 400, "INVALID_INPUT");
        }
        for (Object numericReason : List.of(0, 1, "0", "1")) {
            var coerced = new LinkedHashMap<>(event); coerced.put("reason", numericReason);
            rejected(browser.post("/sessions/" + session + "/selections", Map.of("events", List.of(coerced))), 400, "INVALID_INPUT");
        }
        event.put("elapsedMs", 7000);
        rejected(browser.post("/sessions/" + session + "/selections", Map.of("events", List.of(event))), 422, "INVALID_SELECTION");
        rejected(browser.post("/sessions/" + session + "/shares", Map.of("unexpected", true)), 400, "INVALID_INPUT");
        rejected(browser.post("/sessions/" + session + "/shares", null), 409, "SESSION_NOT_COMPLETED");
    }
    @Test void securityHeadersBodyLimitAndIdempotencyAreEnforced() throws Exception {
        var browser = new Browser();
        rejected(browser.request("POST", "/generation-jobs", json.write(input(8)), Map.of()), 400, "INVALID_INPUT");
        rejected(browser.request("POST", "/generation-jobs", json.write(input(8)), Map.of("Idempotency-Key", "key", "Origin", "https://evil.example")), 400, "INVALID_INPUT");
        rejected(browser.request("POST", "/generation-jobs", json.write(input(8)), Map.of("Idempotency-Key", "key", "Sec-Fetch-Site", "cross-site")), 400, "INVALID_INPUT");
        rejected(browser.request("POST", "/generation-jobs", "x".repeat(65537), Map.of("Idempotency-Key", "key")), 413, "INVALID_INPUT");
        rejected(browser.request("DELETE", "/drafts/any", null, Map.of()), 405, "INVALID_INPUT");
        String key = "stable-key";
        var created = accepted(browser.post("/generation-jobs", input(8), key), 202, "GenerationJob");
        assertThat(accepted(browser.post("/generation-jobs", input(8), key), 202, "GenerationJob")).isEqualTo(created);
        rejected(browser.post("/generation-jobs", input(16), key), 409, "IDEMPOTENCY_CONFLICT");
        rejected(browser.get("/generation-jobs/not-a-uuid"), 404, "NOT_FOUND");
    }
    @Test void spoofedForwardedAddressesCannotBypassIpLimit() throws Exception {
        for (int i = 0; i < 5; i++) {
            var browser = new Browser();
            accepted(browser.request("POST", "/generation-jobs", json.write(input(8)), Map.of("Idempotency-Key", "key", "X-Forwarded-For", "198.51.100." + i)), 202, "GenerationJob");
        }
        var response = new Browser().request("POST", "/generation-jobs", json.write(input(8)), Map.of("Idempotency-Key", "key", "X-Forwarded-For", "203.0.113.1"));
        rejected(response, 429, "RATE_LIMITED");
        assertThat(Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow())).isBetween(1L, 600L);
    }
    @Test void wrongGetAndPostMethodsReturn405NotRetryable500() throws Exception {
        var browser = new Browser();
        var get = browser.get("/generation-jobs");
        rejected(get, 405, "INVALID_INPUT");
        assertThat(get.headers().firstValue("Allow")).contains("POST");
        var post = browser.post("/health", null);
        rejected(post, 405, "INVALID_INPUT");
        assertThat(post.headers().firstValue("Allow").orElseThrow()).contains("GET");
        assertThat(json.read(post.body(), JsonNode.class).get("retryable").asBoolean()).isFalse();
    }
}
