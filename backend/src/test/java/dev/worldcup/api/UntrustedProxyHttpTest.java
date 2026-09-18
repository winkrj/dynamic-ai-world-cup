package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.support.EngineTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "TRUSTED_PROXY_ADDRESS=172.30.52.2")
@ActiveProfiles("proxy")
@Import(EngineTestConfiguration.class)
class UntrustedProxyHttpTest extends ProxyHttpSupport {
    @Test void untrustedSocketPeerCannotClaimForwardedClientAddresses() throws Exception {
        for (int i = 0; i < 5; i++) {
            accepted(new Browser("198.51.100." + i).generate(), 202);
        }
        rateLimited(new Browser("203.0.113.80").generate());
        assertThat(eventsFor("127.0.0.1")).isEqualTo(5);
        assertThat(eventsFor("203.0.113.80")).isZero();
    }
}
