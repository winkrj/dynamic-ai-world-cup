package dev.worldcup.api;

import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Local dependencies only: never calls a paid provider or claims model quality/account balance. */
@RestController
public class ReadinessController {
    public record Readiness(String status, String service) {}
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final Resource webIndex;

    public ReadinessController(JdbcTemplate jdbc, Environment environment, ResourceLoader resources) {
        this.jdbc = jdbc;
        this.environment = environment;
        this.webIndex = resources.getResource("classpath:static/index.html");
    }

    @GetMapping("/api/v1/ready")
    public ResponseEntity<Readiness> ready() {
        boolean engineConfigured = environment.matchesProfiles("live & !dev")
                || environment.matchesProfiles("dev & !live & !prod");
        boolean ready = engineConfigured && environment.getProperty("worldcup.worker.enabled", Boolean.class, false)
                && webIndex.exists() && databaseReady();
        return ResponseEntity.status(ready ? 200 : 503)
                .body(new Readiness(ready ? "READY" : "NOT_READY", "dynamic-ai-world-cup"));
    }

    private boolean databaseReady() {
        try {
            return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection -> {
                try (var statement = connection.createStatement()) {
                    statement.setQueryTimeout(2);
                    try (var result = statement.executeQuery("SELECT 1")) {
                        return result.next() && result.getInt(1) == 1;
                    }
                }
            }));
        } catch (RuntimeException unavailable) {
            // Connection messages can contain credentials and internal addresses.
            return false;
        }
    }
}
