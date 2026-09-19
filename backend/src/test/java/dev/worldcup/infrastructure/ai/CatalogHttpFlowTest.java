package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;

import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.support.PostPreviewHttpFlow;
import dev.worldcup.support.PostgresSupport;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/** Real DB/HTTP, but zero paid calls: even an accidental miss is stopped by budget=0. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "worldcup.ai.strategy=catalog", "worldcup.ai.api-key=sk-test-not-real", "worldcup.ai.budget-usd=0"})
@ActiveProfiles("live")
class CatalogHttpFlowTest extends PostgresSupport {
    @Value("${local.server.port}") int port;
    @Autowired GenerationWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonCodec json;

    @Test void curatedDbPresetRegeneratesThenFreezesAndSharesWithNoProviderOrFalseApproval() throws Exception {
        int callsBefore = jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class);
        int approvalsBefore = jdbc.queryForObject("SELECT count(*) FROM candidate_reuse_set", Integer.class);
        try (var http = HttpClient.newHttpClient()) {
            var owner = new Browser(http);
            var outsider = new Browser(http);
            var queued = owner.request("POST", "/generation-jobs", Map.of("prompt", "취미 추천해줘", "size", 16,
                    "locale", "ko-KR", "timezone", "Asia/Seoul"), UUID.randomUUID().toString(), 202, "GenerationJob");
            long start = System.nanoTime();
            assertThat(worker.runOne()).isTrue();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            var ready = owner.request("GET", "/generation-jobs/" + queued.path("jobId").asString(), null, null, 200, "GenerationJob");
            assertThat(ready.path("status").asString()).isEqualTo("READY");
            String draftPath = "/drafts/" + ready.path("draftId").asString();
            var preview = owner.request("GET", draftPath, null, null, 200, "Preview");
            assertThat(preview.path("candidates").size()).isEqualTo(16);
            var regen = owner.request("POST", draftPath + "/regenerations", Map.of("expectedVersion", preview.path("version").asInt()),
                    UUID.randomUUID().toString(), 202, "GenerationJob");
            assertThat(worker.runOne()).isTrue();
            assertThat(owner.request("GET", "/generation-jobs/" + regen.path("jobId").asString(), null, null, 200,
                    "GenerationJob").path("status").asString()).isEqualTo("READY");
            var replacement = owner.request("GET", draftPath, null, null, 200, "Preview");
            assertThat(replacement.path("candidates")).isNotEqualTo(preview.path("candidates"));
            PostPreviewHttpFlow.complete(replacement, json, owner::request, outsider::request);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class)).isEqualTo(callsBefore);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_reuse_set", Integer.class)).isEqualTo(approvalsBefore);
            System.out.println("Catalog DB-only worker elapsed ms (local, not production SLA): " + elapsed);
        }
    }

    private final class Browser {
        private final HttpClient http;
        private String cookie;
        Browser(HttpClient http) { this.http = http; }
        JsonNode request(String method, String path, Object body, String key, int status, String schema) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path)).timeout(Duration.ofSeconds(10));
            if (cookie != null) builder.header("Cookie", cookie);
            if (key != null) builder.header("Idempotency-Key", key).header("Origin", "https://worldcup.example");
            if (body != null) builder.header("Content-Type", "application/json");
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(body)));
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).describedAs("%s %s: %s", method, path, response.body()).isEqualTo(status);
            response.headers().firstValue("Set-Cookie").ifPresent(v -> cookie = v.split(";", 2)[0]);
            return json.read(response.body(), JsonNode.class);
        }
    }
}
