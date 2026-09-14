package dev.worldcup.api;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Never derive public links or trusted origins from an untrusted Host/forwarding header. */
@Component
public class PublicAddress {
    private final String origin;
    public PublicAddress(@Value("${worldcup.public-origin}") String origin,
                         @Value("${worldcup.cookie-secure}") boolean secure, Environment environment) {
        if (environment.matchesProfiles("prod & dev")) {
            throw new IllegalArgumentException("Do not combine prod and dev profiles");
        }
        if (environment.matchesProfiles("prod") && !secure) {
            throw new IllegalArgumentException("Production requires Secure cookies");
        }
        URI uri = URI.create(origin);
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !(uri.getPath().isEmpty()) || !("https".equals(uri.getScheme())
                || (!secure && "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))))) {
            throw new IllegalArgumentException("PUBLIC_ORIGIN must be an exact HTTPS origin (local HTTP only in dev)");
        }
        this.origin = origin;
    }
    public String origin() { return origin; }
    public String share(String token) { return origin + "/shares/" + token; }
}
