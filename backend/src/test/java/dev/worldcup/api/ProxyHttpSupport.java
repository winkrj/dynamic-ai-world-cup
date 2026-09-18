package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.GenerationWorker;
import dev.worldcup.identity.ActorService;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.support.ControlledEngine;
import dev.worldcup.support.PostgresSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

/** Real embedded-Tomcat requests and disposable PostgreSQL, without paid generation. */
abstract class ProxyHttpSupport extends PostgresSupport {
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired GenerationWorker worker;
    @Autowired ControlledEngine engine;
    @Autowired JsonCodec json;
    HttpClient http;

    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota CASCADE");
        engine.reset();
        http = HttpClient.newHttpClient();
    }

    @AfterEach void close() { http.close(); }

    final class Browser {
        private final String forwardedFor;
        private String cookie;
        Browser(String forwardedFor) { this.forwardedFor = forwardedFor; }

        HttpResponse<String> request(String method, String path, Object body, String key,
                                     Map<String, String> overrides) throws Exception {
            var headers = new LinkedHashMap<String, String>(Map.of(
                    "Origin", "https://worldcup.example", "X-Forwarded-For", forwardedFor,
                    "X-Forwarded-Proto", "https", "X-Forwarded-Host", "evil.example",
                    "X-Forwarded-Port", "4443", "Forwarded", "for=192.0.2.99;host=evil.example;proto=http"));
            if (key != null) headers.put("Idempotency-Key", key);
            if (cookie != null) headers.put("Cookie", cookie);
            if (body != null) headers.put("Content-Type", "application/json");
            headers.putAll(overrides);
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
            headers.forEach(request::header);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.write(body)));
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Set-Cookie").ifPresent(value -> cookie = value.split(";", 2)[0]);
            return response;
        }

        HttpResponse<String> generate() throws Exception {
            return request("POST", "/generation-jobs", Map.of("prompt", "새 취미", "size", 8,
                    "locale", "ko-KR", "timezone", "Asia/Seoul"), UUID.randomUUID().toString(), Map.of());
        }
    }

    JsonNode accepted(HttpResponse<String> response, int status) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
        return json.read(response.body(), JsonNode.class);
    }

    void rateLimited(HttpResponse<String> response) {
        assertThat(accepted(response, 429).path("code").asString()).isEqualTo("RATE_LIMITED");
    }

    int eventsFor(String address) {
        return jdbc.queryForObject("SELECT count(*) FROM generation_rate_event WHERE scope = ?",
                Integer.class, "ip:" + ActorService.hash(address));
    }
}
