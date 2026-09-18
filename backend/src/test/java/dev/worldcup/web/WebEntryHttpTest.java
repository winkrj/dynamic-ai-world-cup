package dev.worldcup.web;

import static org.assertj.core.api.Assertions.assertThat;

import dev.worldcup.support.PostgresSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.web.resources.static-locations=classpath:/web-entry-fixture/")
class WebEntryHttpTest extends PostgresSupport {
    private static final String DOCUMENT_MARKER = "web-entry-test-document";
    @Value("${local.server.port}") int port;

    @ParameterizedTest @ValueSource(strings = {"/", "/shares/public-token", "/shares/another-token?from=link"})
    void servesTheSameDocumentForExplicitEntryRoutes(String path) throws Exception {
        var response = request("GET", path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("text/html"));
        assertThat(response.body()).contains(DOCUMENT_MARKER);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"/", "/shares/public-token"})
    void headHasHeadersWithoutADocumentBody(String path) throws Exception {
        var response = request("HEAD", path);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("text/html"));
        assertThat(response.body()).isEmpty();
    }

    @ParameterizedTest @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void mutationMethodsDoNotEnterTheApplication(String method) throws Exception {
        for (String path : List.of("/", "/shares/public-token")) {
            var response = request(method, path);
            assertError(response, 405, "INVALID_INPUT");
            assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(value -> assertThat(value).contains("GET"));
        }
    }

    @ParameterizedTest @ValueSource(strings = {"/api/v1/missing", "/api/v1/shares/missing", "/assets/missing.js",
            "/unmapped", "/shares", "/shares/public-token/nested"})
    void missingApisAssetsAndUnmappedRoutesRemainJson404(String path) throws Exception {
        assertError(request("GET", path), 404, "NOT_FOUND");
    }

    @Test void existingApiStillRespondsWithJson() throws Exception {
        var response = request("GET", "/api/v1/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        assertThat(JsonMapper.builder().build().readTree(response.body()).get("status").asString()).isEqualTo("UP");
        assertThat(response.body()).doesNotContain(DOCUMENT_MARKER);
    }

    private HttpResponse<String> request(String method, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, HttpRequest.BodyPublishers.noBody()).build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).describedAs(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("application/json"));
        var error = JsonMapper.builder().build().readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo(code);
        assertThat(response.headers().firstValue("X-Request-Id")).contains(error.get("requestId").asString());
        assertThat(response.body()).doesNotContain(DOCUMENT_MARKER, "<!doctype html>");
    }
}
