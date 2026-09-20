package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.support.PostgresSupport;
import dev.worldcup.support.PostPreviewHttpFlow;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** Explicit paid experiment only. Ordinary test/verify never calls a live provider. */
@EnabledIfEnvironmentVariable(named = "CANDIDATE_LIVE_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("live")
@Import(LiveEngineHttpTest.CaptureConfiguration.class)
class LiveEngineHttpTest extends PostgresSupport {
    private static final List<OpenAiResponsesClient.Exchange> EXCHANGES = new ArrayList<>();
    @Value("${local.server.port}") int port;
    @Autowired GenerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;
    @TestConfiguration static class CaptureConfiguration {
        @Bean @Primary OpenAiResponsesClient capturedClient(ProviderCallLedger ledger, Clock clock,
                @Value("${worldcup.ai.api-key}") String key) {
            return new OpenAiResponsesClient(URI.create("https://api.openai.com/v1/responses"), key, ledger, clock,
                    Duration.ofSeconds(90), EXCHANGES::add);
        }
    }
    @Test void realProviderPassesThroughWorkerAndUnchangedHttpPreviewContract() throws Exception {
        int size = Integer.parseInt(System.getenv().getOrDefault("CANDIDATE_LIVE_SIZE", "8"));
        String caseId = System.getenv().getOrDefault("CANDIDATE_LIVE_CASE", "hobby-calibration");
        boolean catalogCase = caseId.startsWith("catalog-");
        var selected = LiveEvalCase.select(caseId, size,
                json.read(Files.readString(Path.of(catalogCase ? "../evals/catalog-cases.json" : "../evals/cases.json")), JsonNode.class));
        Path output = Path.of("../reports/local/live-engine", Instant.now().toString().replace(':', '-') + "-size" + size);
        Files.createDirectories(output);
        String prompt = selected.prompt();
        var samples = new ArrayList<Map<String, Object>>();
        long start = System.nanoTime();
        try (var http = HttpClient.newHttpClient()) {
            var submitted = http.send(HttpRequest.newBuilder(URI.create(base() + "/generation-jobs"))
                    .header("Content-Type", "application/json").header("Origin", "https://worldcup.example")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(json.write(Map.of("prompt", prompt, "size", size, "locale", "ko-KR", "timezone", "Asia/Seoul")))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(submitted.statusCode()).isEqualTo(202);
            var queued = json.read(submitted.body(), JsonNode.class);
            samples.add(Map.of("schema", "GenerationJob", "value", queued));
            String cookie = submitted.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            assertThat(worker.runOne()).isTrue();
            var response = get(http, "/generation-jobs/" + queued.path("jobId").asString(), cookie);
            samples.add(Map.of("schema", "GenerationJob", "value", response));
            Files.writeString(output.resolve("job.json"), json.write(response));
            int calls = EXCHANGES.size();
            if (catalogCase) {
                assertThat(System.getenv("CANDIDATE_ENGINE_STRATEGY")).isEqualTo("catalog");
                assertThat(calls).isEqualTo("catalog-preset".equals(caseId) ? 0 : 1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_reuse_set", Integer.class)).isZero();
            }
            if (assertExpectedTerminal(caseId, response)) return;
            var preview = get(http, "/drafts/" + response.path("draftId").asString(), cookie);
            samples.add(Map.of("schema", "Preview", "value", preview));
            assertThat(preview.path("candidates").size()).isEqualTo(size);
            assertThat(preview.toString()).doesNotContain("[개발용]", "assessments", "requirements", "sk-", prompt);
            Files.writeString(output.resolve("preview.json"), json.write(preview));
            int ledgerRows = jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class);
            var owner = new FlowBrowser(http, cookie, samples);
            var outsider = new FlowBrowser(http, null, samples);
            var played = PostPreviewHttpFlow.complete(preview, json, owner::request, outsider::request);
            assertThat(EXCHANGES).hasSize(calls);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class)).isEqualTo(ledgerRows);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class)).isEqualTo(1);
            Files.writeString(output.resolve("playback.json"), json.write(played));
        } finally {
            Files.writeString(output.resolve("exchanges.private.json"), json.write(EXCHANGES));
            Files.writeString(output.resolve("ledger.json"), json.write(jdbc.queryForList("SELECT * FROM provider_call ORDER BY created_at, id")));
            Files.writeString(output.resolve("http-samples.json"), json.write(samples));
            Files.writeString(output.resolve("run.json"), json.write(Map.of("size", size, "prompt", prompt,
                    "caseId", selected.id(), "datasetVersion", selected.datasetVersion(),
                    "latencyMs", (System.nanoTime() - start) / 1_000_000, "promptVersion", catalogCase ? OpenAiResponsesClient.COMPACT_PROMPT_VERSION : OpenAiResponsesClient.PROMPT_VERSION,
                    "scope", "single synthetic end-to-end case; not a formal human quality evaluation")));
            System.out.println("Live eval artifacts: " + output.toAbsolutePath().normalize());
        }
    }
    /** True means an expected refusal: no preview, game, or fabricated success may follow. */
    static boolean assertExpectedTerminal(String caseId, JsonNode response) {
        if ("catalog-facts".equals(caseId)) {
            assertThat(response.path("status").asString()).isEqualTo("FAILED");
            assertThat(response.path("error").path("code").asString()).isEqualTo("GROUNDING_REQUIRED");
            assertThat(response.has("draftId")).isTrue();
            assertThat(response.path("draftId").isNull()).isTrue();
            return true;
        }
        assertThat(response.path("status").asString()).describedAs("Safe job result: %s", response).isEqualTo("READY");
        return false;
    }
    private String base() { return "http://localhost:" + port + "/api/v1"; }
    private final class FlowBrowser {
        private final HttpClient http;
        private final List<Map<String, Object>> samples;
        private String cookie;
        FlowBrowser(HttpClient http, String cookie, List<Map<String, Object>> samples) {
            this.http = http; this.cookie = cookie; this.samples = samples;
        }
        JsonNode request(String method, String path, Object body, String key, int status, String schema) throws Exception {
            var request = HttpRequest.newBuilder(URI.create(base() + path)).timeout(Duration.ofSeconds(10));
            if (cookie != null) request.header("Cookie", cookie);
            if (key != null) request.header("Idempotency-Key", key).header("Origin", "https://worldcup.example");
            if (body != null) request.header("Content-Type", "application/json");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).describedAs("Post-preview %s %s", method, path).isEqualTo(status);
            response.headers().firstValue("Set-Cookie").ifPresent(value -> cookie = value.split(";", 2)[0]);
            var value = json.read(response.body(), JsonNode.class);
            samples.add(Map.of("schema", schema, "value", value));
            return value;
        }
    }
    private JsonNode get(HttpClient http, String path, String cookie) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.read(response.body(), JsonNode.class);
    }
}
