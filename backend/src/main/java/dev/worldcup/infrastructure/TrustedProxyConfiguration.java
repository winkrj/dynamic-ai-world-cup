package dev.worldcup.infrastructure;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fail closed instead of inheriting Tomcat's broad private-network proxy trust defaults. */
@Component
@Profile("proxy")
public final class TrustedProxyConfiguration {
    public TrustedProxyConfiguration(Environment environment) {
        String address = environment.getProperty("TRUSTED_PROXY_ADDRESS", "");
        requireLiteral(address);
        require(environment, "server.forward-headers-strategy", "native");
        require(environment, "server.tomcat.remoteip.internal-proxies", Pattern.quote(address));
        require(environment, "server.tomcat.remoteip.trusted-proxies", "");
        require(environment, "server.tomcat.remoteip.remote-ip-header", "X-Forwarded-For");
        require(environment, "server.tomcat.remoteip.protocol-header", "X-Forwarded-Proto");
        require(environment, "server.tomcat.remoteip.protocol-header-https-value", "https");
        require(environment, "server.tomcat.remoteip.host-header", "");
        require(environment, "server.tomcat.remoteip.port-header", "");
    }

    private static void requireLiteral(String address) {
        // Restrict to literal syntax before the JDK parser: never resolve a hostname or accept CIDR/regex.
        if (!address.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F]*:[0-9a-fA-F:.]*")) {
            throw new IllegalArgumentException("TRUSTED_PROXY_ADDRESS must be one exact IP literal");
        }
        try {
            if (InetAddress.getByName(address).isAnyLocalAddress()) {
                throw new IllegalArgumentException("TRUSTED_PROXY_ADDRESS cannot be a wildcard address");
            }
        } catch (UnknownHostException exception) {
            throw new IllegalArgumentException("TRUSTED_PROXY_ADDRESS must be one exact IP literal");
        }
    }

    private static void require(Environment environment, String property, String expected) {
        if (!expected.equals(environment.getProperty(property))) {
            throw new IllegalArgumentException("Proxy profile requires its restricted setting for " + property);
        }
    }
}
