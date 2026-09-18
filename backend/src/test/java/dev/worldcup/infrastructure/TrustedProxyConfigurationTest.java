package dev.worldcup.infrastructure;

import static org.assertj.core.api.Assertions.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.mock.env.MockEnvironment;

class TrustedProxyConfigurationTest {
    private MockEnvironment environment(String peer) throws IOException {
        var environment = new MockEnvironment().withProperty("TRUSTED_PROXY_ADDRESS", peer);
        environment.getPropertySources().addLast(new PropertiesPropertySource("proxy",
                PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-proxy.properties"))));
        return environment;
    }

    @ParameterizedTest @ValueSource(strings = {"172.30.52.2", "127.0.0.1", "::1", "fd00::2"})
    void profileAcceptsOnlyOneExplicitPeerAndQuotesItsLiteral(String peer) throws Exception {
        var environment = environment(peer);
        assertThatCode(() -> new TrustedProxyConfiguration(environment)).doesNotThrowAnyException();
        var pattern = java.util.regex.Pattern.compile(environment.getRequiredProperty("server.tomcat.remoteip.internal-proxies"));
        assertThat(pattern.matcher(peer).matches()).isTrue();
        assertThat(pattern.matcher(peer.replace('.', 'x')).matches()).isEqualTo(!peer.contains("."));
    }

    @ParameterizedTest @ValueSource(strings = {"", " ", ".*", "0.0.0.0", "::", "172.30.52.0/24",
            "10.0.0.0/8", "172.30.52.2,172.30.52.3", "localhost", "127.1", "2130706433",
            "999.1.1.1", "[::1]", "fe80::1%lo0", "fd00::not-an-ip", " 172.30.52.2"})
    void rejectsMissingBroadOrNonliteralPeer(String peer) throws Exception {
        var environment = environment(peer);
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedProxyConfiguration(environment));
    }

    @ParameterizedTest @ValueSource(strings = {"server.forward-headers-strategy", "server.tomcat.remoteip.internal-proxies",
            "server.tomcat.remoteip.trusted-proxies", "server.tomcat.remoteip.remote-ip-header",
            "server.tomcat.remoteip.protocol-header", "server.tomcat.remoteip.protocol-header-https-value",
            "server.tomcat.remoteip.host-header", "server.tomcat.remoteip.port-header"})
    void refusesOverridesThatWidenTheTrustBoundary(String property) throws Exception {
        var environment = environment("172.30.52.2").withProperty(property, "unsafe-override");
        assertThatIllegalArgumentException().isThrownBy(() -> new TrustedProxyConfiguration(environment));
    }

    @Test void ordinaryProfilesStillIgnoreForwardingHeaders() throws Exception {
        var properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
        assertThat(properties.getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }
}
