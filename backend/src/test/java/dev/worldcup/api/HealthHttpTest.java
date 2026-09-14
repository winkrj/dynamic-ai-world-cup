package dev.worldcup.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthHttpTest extends dev.worldcup.support.PostgresSupport {
    @Value("${local.server.port}") int port;

    @Test void healthRespondsOverRealHttp() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/health")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            var json = JsonMapper.builder().build().readTree(response.body());
            assertThat(json.get("status").asString()).isEqualTo("UP");
            assertThat(json.get("service").asString()).isEqualTo("dynamic-ai-world-cup");
        }
    }
}
