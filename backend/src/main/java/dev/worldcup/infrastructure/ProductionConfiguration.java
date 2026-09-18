package dev.worldcup.infrastructure;

import java.net.URI;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** Reject an apparently healthy deployment that cannot serve the actual product. */
@Component
@Profile("prod")
public final class ProductionConfiguration {
    public ProductionConfiguration(Environment environment, ResourceLoader resources) {
        if (!environment.matchesProfiles("live & !dev")) {
            throw new IllegalArgumentException("Production requires live without dev");
        }
        String origin = environment.getProperty("worldcup.public-origin", "");
        URI uri = URI.create(origin);
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost().toLowerCase(java.util.Locale.ROOT))
                || !environment.getProperty("worldcup.cookie-secure", Boolean.class, false)) {
            throw new IllegalArgumentException("Production requires an explicit public HTTPS origin and Secure cookies");
        }
        String database = environment.getProperty("spring.datasource.url", "");
        String user = environment.getProperty("spring.datasource.username", "");
        String password = environment.getProperty("spring.datasource.password", "");
        if (!database.startsWith("jdbc:postgresql://") || user.isBlank() || password.isBlank()
                || "worldcup-local-only".equals(password)) {
            throw new IllegalArgumentException("Production requires explicit PostgreSQL credentials, not local defaults");
        }
        if (!environment.getProperty("worldcup.worker.enabled", Boolean.class, false)
                || !environment.getProperty("worldcup.retention.enabled", Boolean.class, false)) {
            throw new IllegalArgumentException("Production requires generation and retention workers");
        }
        if (!resources.getResource("classpath:static/index.html").exists()) {
            throw new IllegalArgumentException("Production requires the combined frontend application artifact");
        }
    }
}
