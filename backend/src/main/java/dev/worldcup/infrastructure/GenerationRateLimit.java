package dev.worldcup.infrastructure;

import static dev.worldcup.shared.Failure.Code.RATE_LIMITED;
import dev.worldcup.identity.ActorService;
import dev.worldcup.shared.Failure;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Sliding ten-minute quota, serialized by actor and hashed socket peer IP. Called inside mutation tx. */
@Component
public class GenerationRateLimit {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public GenerationRateLimit(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    public void reserve(String actor, String remoteAddress) {
        var now = Timestamp.from(clock.instant());
        var since = Timestamp.from(clock.instant().minus(Duration.ofMinutes(10)));
        for (String scope : Stream.of("actor:" + actor, "ip:" + ActorService.hash(remoteAddress)).sorted().toList()) {
            jdbc.update("INSERT INTO generation_quota(scope) VALUES (?) ON CONFLICT DO NOTHING", scope);
            jdbc.queryForObject("SELECT scope FROM generation_quota WHERE scope = ? FOR UPDATE", String.class, scope);
            int count = jdbc.queryForObject("SELECT count(*) FROM generation_rate_event WHERE scope = ? AND created_at > ?", Integer.class, scope, since);
            if (count >= 5) throw Failure.of(RATE_LIMITED);
            jdbc.update("INSERT INTO generation_rate_event(scope, created_at) VALUES (?, ?)", scope, now);
            jdbc.update("UPDATE generation_quota SET last_used_at = ? WHERE scope = ?", now, scope);
        }
    }
}
