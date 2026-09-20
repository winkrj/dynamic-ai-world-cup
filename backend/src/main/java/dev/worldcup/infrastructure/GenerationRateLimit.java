package dev.worldcup.infrastructure;

import dev.worldcup.identity.ActorService;
import dev.worldcup.shared.Failure;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Accepted jobs consume actor/IP burst slots and, when enabled, daily actor slots in the mutation transaction. */
@Component
public class GenerationRateLimit {
    private static final ZoneId DAILY_ZONE = ZoneId.of("Asia/Seoul");
    private static final Duration BURST_WINDOW = Duration.ofMinutes(10);
    private static final int BURST_LIMIT = 5;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int dailyLimit;
    public GenerationRateLimit(JdbcTemplate jdbc, Clock clock,
            @Value("${worldcup.generation.daily-limit:2}") int dailyLimit) {
        if (dailyLimit < 0) throw new IllegalArgumentException("Daily generation limit must be nonnegative; zero disables the daily cap");
        this.jdbc = jdbc; this.clock = clock; this.dailyLimit = dailyLimit;
    }
    public void reserve(String actor, String remoteAddress) {
        String actorScope = "actor:" + actor;
        var scopes = Stream.of(actorScope, "ip:" + ActorService.hash(remoteAddress)).sorted().toList();
        for (String scope : scopes) {
            jdbc.update("INSERT INTO generation_quota(scope) VALUES (?) ON CONFLICT DO NOTHING", scope);
            jdbc.queryForObject("SELECT scope FROM generation_quota WHERE scope = ? FOR UPDATE", String.class, scope);
        }
        // A lock wait may cross midnight. Decide the accounting day only after acquiring both locks.
        Instant now = clock.instant();
        Instant retryAt = now;
        if (dailyLimit > 0) {
            var day = now.atZone(DAILY_ZONE).toLocalDate();
            Instant dayStart = day.atStartOfDay(DAILY_ZONE).toInstant();
            Instant nextDay = day.plusDays(1).atStartOfDay(DAILY_ZONE).toInstant();
            int dailyCount = jdbc.queryForObject("""
                    SELECT count(*) FROM generation_rate_event
                    WHERE scope = ? AND created_at >= ? AND created_at < ?
                    """, Integer.class, actorScope, Timestamp.from(dayStart), Timestamp.from(nextDay));
            if (dailyCount >= dailyLimit) retryAt = nextDay;
        }
        for (String scope : scopes) {
            // The fifth newest event must expire, even if a previous configuration admitted more.
            var recent = jdbc.query("""
                    SELECT created_at FROM generation_rate_event WHERE scope = ? AND created_at > ?
                    ORDER BY created_at DESC LIMIT ?
                    """, (row, index) -> row.getTimestamp(1).toInstant(), scope,
                    Timestamp.from(now.minus(BURST_WINDOW)), BURST_LIMIT);
            if (recent.size() == BURST_LIMIT) {
                Instant burstReset = recent.getLast().plus(BURST_WINDOW);
                if (burstReset.isAfter(retryAt)) retryAt = burstReset;
            }
        }
        if (retryAt.isAfter(now)) {
            Duration wait = Duration.between(now, retryAt);
            throw Failure.rateLimited(wait.getSeconds() + (wait.getNano() == 0 ? 0 : 1));
        }
        for (String scope : scopes) {
            jdbc.update("INSERT INTO generation_rate_event(scope, created_at) VALUES (?, ?)", scope, Timestamp.from(now));
            jdbc.update("UPDATE generation_quota SET last_used_at = ? WHERE scope = ?", Timestamp.from(now), scope);
        }
    }
}
