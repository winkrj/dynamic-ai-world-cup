package dev.worldcup.infrastructure;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RetentionService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    public RetentionService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }
    @Transactional public void clean() {
        var now = clock.instant();
        var day = Timestamp.from(now.minus(Duration.ofDays(1)));
        var month = Timestamp.from(now.minus(Duration.ofDays(30)));
        jdbc.update("DELETE FROM idempotency_request WHERE created_at < ?", day);
        jdbc.update("DELETE FROM generation_job WHERE state IN ('READY', 'FAILED') AND created_at < ?", day);
        jdbc.update("""
                DELETE FROM draft d WHERE d.created_at < ? AND d.state <> 'REGENERATING'
                AND NOT EXISTS (SELECT 1 FROM generation_job j WHERE j.draft_id = d.id AND j.state IN ('QUEUED', 'RUNNING'))
                """, day);
        jdbc.update("DELETE FROM play_session WHERE created_at < ?", month);
        // Keep the entire current Seoul day; burst expiry is not daily quota expiry.
        jdbc.update("DELETE FROM generation_rate_event WHERE created_at < ?", day);
        jdbc.update("DELETE FROM generation_quota WHERE last_used_at < ?", day);
        // Public snapshots and links are intentionally untouched; owner hashes remain for access control.
        jdbc.update("""
                DELETE FROM anonymous_actor a WHERE created_at < ?
                AND NOT EXISTS (SELECT 1 FROM draft WHERE actor_id = a.id)
                AND NOT EXISTS (SELECT 1 FROM generation_job WHERE actor_id = a.id)
                AND NOT EXISTS (SELECT 1 FROM play_session WHERE actor_id = a.id)
                AND NOT EXISTS (SELECT 1 FROM bracket_snapshot WHERE actor_id = a.id)
                AND NOT EXISTS (SELECT 1 FROM idempotency_request WHERE actor_id = a.id)
                """, month);
    }
}
