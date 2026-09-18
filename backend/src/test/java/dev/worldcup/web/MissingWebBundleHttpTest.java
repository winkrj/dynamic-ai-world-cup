package dev.worldcup.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.worldcup.support.PostgresSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MissingWebBundleHttpTest extends PostgresSupport {
    @Value("${local.server.port}") int port;

    @ParameterizedTest @ValueSource(strings = {"/", "/shares/public-token"})
    void backendWithoutBundledFrontendReturnsJson404(String path) throws Exception {
        assertThat(getClass().getClassLoader().getResource("static/index.html")).isNull();
        try (var client = HttpClient.newHttpClient()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(404);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
            var error = JsonMapper.builder().build().readTree(response.body());
            assertThat(error.get("code").asString()).isEqualTo("NOT_FOUND");
            assertThat(response.headers().firstValue("X-Request-Id")).contains(error.get("requestId").asString());
            assertThat(response.body()).doesNotContain("web-entry-test-document", "<!doctype html>");
        }
    }
}
