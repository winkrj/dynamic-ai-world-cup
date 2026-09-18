package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.support.EngineTestConfiguration;
import dev.worldcup.support.PostPreviewHttpFlow;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "TRUSTED_PROXY_ADDRESS=127.0.0.1")
@ActiveProfiles("proxy")
@Import(EngineTestConfiguration.class)
class TrustedProxyHttpTest extends ProxyHttpSupport {
    @Test void distinctRightmostClientsHaveIndependentIpQuotas() throws Exception {
        for (int i = 0; i < 5; i++) {
            accepted(new Browser("192.0.2.1, 198.51.100.10").generate(), 202);
            accepted(new Browser("192.0.2.1, 198.51.100.20").generate(), 202);
        }
        rateLimited(new Browser("198.51.100.10").generate());
        rateLimited(new Browser("198.51.100.20").generate());
        assertThat(eventsFor("198.51.100.10")).isEqualTo(5);
        assertThat(eventsFor("198.51.100.20")).isEqualTo(5);
        assertThat(eventsFor("127.0.0.1")).isZero();
    }

    @Test void changingSpoofedLeftAddressesCannotBypassTheRightmostClientQuota() throws Exception {
        for (int i = 0; i < 5; i++) {
            accepted(new Browser("192.0.2." + i + ", 198.51.100.40").generate(), 202);
        }
        rateLimited(new Browser("127.0.0.1, 203.0.113.55, 198.51.100.40").generate());
        assertThat(eventsFor("198.51.100.40")).isEqualTo(5);
        assertThat(eventsFor("203.0.113.55")).isZero();
    }

    @Test void spoofedForwardedHostCannotChangeAllowedOriginOrShareUrl() throws Exception {
        var owner = new Browser("198.51.100.30");
        var rejected = owner.request("POST", "/generation-jobs", Map.of("prompt", "새 취미", "size", 8,
                        "locale", "ko-KR", "timezone", "Asia/Seoul"), UUID.randomUUID().toString(),
                Map.of("Origin", "https://evil.example"));
        assertThat(accepted(rejected, 400).path("code").asString()).isEqualTo("INVALID_INPUT");
        var queued = accepted(owner.generate(), 202);
        worker.runOne();
        var job = accepted(owner.request("GET", "/generation-jobs/" + queued.path("jobId").asString(),
                null, null, Map.of()), 200);
        assertThat(job.path("status").asString()).isEqualTo("READY");
        var preview = accepted(owner.request("GET", "/drafts/" + job.path("draftId").asString(),
                null, null, Map.of()), 200);
        var outsider = new Browser("198.51.100.31");
        PostPreviewHttpFlow.complete(preview, json,
                (method, path, body, key, status, schema) -> accepted(owner.request(method, path, body, key, Map.of()), status),
                (method, path, body, key, status, schema) -> accepted(outsider.request(method, path, body, key, Map.of()), status));
        assertThat(engine.calls).hasValue(1);
    }
}
