package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.support.PostgresSupport;
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
        assertThat(size).isIn(8, 16, 32);
        Path output = Path.of("../reports/local/live-engine", Instant.now().toString().replace(':', '-') + "-size" + size);
        Files.createDirectories(output);
        String prompt = "집에서 혼자 조용히 하루 30분씩 꾸준히 할 취미를 고르고 싶어";
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
            assertThat(response.path("status").asString()).describedAs("Safe job result: %s", response).isEqualTo("READY");
            var preview = get(http, "/drafts/" + response.path("draftId").asString(), cookie);
            samples.add(Map.of("schema", "Preview", "value", preview));
            assertThat(preview.path("candidates").size()).isEqualTo(size);
            assertThat(preview.toString()).doesNotContain("[개발용]", "assessments", "requirements", "sk-", prompt);
            Files.writeString(output.resolve("preview.json"), json.write(preview));
        } finally {
            Files.writeString(output.resolve("exchanges.private.json"), json.write(EXCHANGES));
            Files.writeString(output.resolve("ledger.json"), json.write(jdbc.queryForList("SELECT * FROM provider_call ORDER BY created_at, id")));
            Files.writeString(output.resolve("http-samples.json"), json.write(samples));
            Files.writeString(output.resolve("run.json"), json.write(Map.of("size", size, "prompt", prompt,
                    "latencyMs", (System.nanoTime() - start) / 1_000_000, "promptVersion", OpenAiResponsesClient.PROMPT_VERSION,
                    "scope", "single synthetic end-to-end case; not a formal human quality evaluation")));
            System.out.println("Live eval artifacts: " + output.toAbsolutePath().normalize());
        }
    }
    private String base() { return "http://localhost:" + port + "/api/v1"; }
    private JsonNode get(HttpClient http, String path, String cookie) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.read(response.body(), JsonNode.class);
    }
}
