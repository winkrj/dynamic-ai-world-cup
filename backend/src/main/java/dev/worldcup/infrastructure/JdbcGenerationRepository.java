package dev.worldcup.infrastructure;

import dev.worldcup.generation.*;
import dev.worldcup.shared.Failure;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcGenerationRepository implements GenerationRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public JdbcGenerationRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }

    @Override public Job create(String actor, String draftId, GenerationInput input) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO generation_job(id, actor_id, draft_id, state, input) VALUES (?, ?, ?, 'QUEUED', ?::jsonb)",
                id, actor, draftId, json.write(input));
        return new Job(id, actor, draftId, "QUEUED", input, 0, null, null);
    }
    @Override public Optional<Job> ownedJob(String actor, String id) {
        return jdbc.query("SELECT * FROM generation_job WHERE id = ? AND actor_id = ?", this::job, id, actor).stream().findFirst();
    }
    @Override public Optional<Draft> ownedDraft(String actor, String id, boolean lock) {
        return jdbc.query("SELECT * FROM draft WHERE id = ? AND actor_id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new Draft(rs.getString("id"), rs.getString("actor_id"), Draft.State.valueOf(rs.getString("state")),
                        rs.getInt("version"), rs.getInt("regeneration_used"), json.read(rs.getString("input"), GenerationInput.class),
                        json.read(rs.getString("content"), DraftContent.class)), id, actor).stream().findFirst();
    }
    @Override public void reserveRegeneration(String draftId) { jdbc.update("UPDATE draft SET state = 'REGENERATING' WHERE id = ?", draftId); }
    @Override public void freeze(String draftId) { jdbc.update("UPDATE draft SET state = 'FROZEN' WHERE id = ?", draftId); }

    @Override public Optional<Job> claim(Instant now) {
        var jobs = jdbc.query("""
                SELECT * FROM generation_job WHERE state = 'QUEUED' AND attempt < 2
                ORDER BY created_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
                """, this::job);
        if (jobs.isEmpty()) return Optional.empty();
        Job pending = jobs.getFirst();
        Instant lease = now.plusSeconds(60);
        jdbc.update("UPDATE generation_job SET state = 'RUNNING', attempt = attempt + 1, lease_until = ? WHERE id = ?",
                Timestamp.from(lease), pending.id());
        return Optional.of(new Job(pending.id(), pending.actorId(), pending.draftId(), "RUNNING", pending.input(), pending.attempt() + 1, lease, null));
    }
    @Override public List<Job> expired(Instant now) {
        return jdbc.query("SELECT * FROM generation_job WHERE state = 'RUNNING' AND lease_until <= ? ORDER BY id FOR UPDATE SKIP LOCKED",
                this::job, Timestamp.from(now));
    }
    @Override public void recover(Job job, Instant now) {
        if (job.attempt() < 2) {
            jdbc.update("UPDATE generation_job SET state = 'QUEUED', lease_until = NULL WHERE id = ? AND state = 'RUNNING' AND attempt = ?",
                    job.id(), job.attempt());
        } else {
            terminalFailure(job, Failure.Code.PROVIDER_UNAVAILABLE, now);
        }
    }
    @Override public boolean complete(Job job, DraftContent content, CandidateEngine.Generated generated, Instant now) {
        if (!ownLiveAttempt(job, now)) return false;
        String draftId = job.draftId();
        if (draftId == null) {
            draftId = UUID.randomUUID().toString();
            jdbc.update("""
                    INSERT INTO draft(id, actor_id, state, version, regeneration_used, input, content)
                    VALUES (?, ?, 'READY', 1, 0, ?::jsonb, ?::jsonb)
                    """, draftId, job.actorId(), json.write(job.input()), json.write(content));
        } else {
            int changed = jdbc.update("""
                    UPDATE draft SET state = 'READY', version = version + 1, regeneration_used = 1, content = ?::jsonb
                    WHERE id = ? AND actor_id = ? AND state = 'REGENERATING' AND regeneration_used = 0
                    """, json.write(content), draftId, job.actorId());
            if (changed != 1) throw new IllegalStateException("Lost regeneration reservation");
        }
        jdbc.update("""
                UPDATE generation_job SET state = 'READY', draft_id = ?, lease_until = NULL,
                provider_version = ?, validator_version = ?, finished_at = ? WHERE id = ?
                """, draftId, generated.providerVersion(), generated.validatorVersion(), Timestamp.from(now), job.id());
        return true;
    }
    @Override public void fail(Job job, Failure.Code error, Instant now) {
        if (ownLiveAttempt(job, now)) terminalFailure(job, error, now);
    }
    private boolean ownLiveAttempt(Job job, Instant now) {
        return !jdbc.query("""
                SELECT id FROM generation_job WHERE id = ? AND state = 'RUNNING' AND attempt = ?
                AND lease_until > ? FOR UPDATE
                """, (rs, n) -> rs.getString(1), job.id(), job.attempt(), Timestamp.from(now)).isEmpty();
    }
    private void terminalFailure(Job job, Failure.Code error, Instant now) {
        jdbc.update("UPDATE generation_job SET state = 'FAILED', error_code = ?, lease_until = NULL, finished_at = ? WHERE id = ?",
                error.name(), Timestamp.from(now), job.id());
        if (job.draftId() != null) jdbc.update("UPDATE draft SET state = 'READY' WHERE id = ? AND state = 'REGENERATING'", job.draftId());
    }
    private Job job(ResultSet rs, int row) throws SQLException {
        Timestamp lease = rs.getTimestamp("lease_until");
        String error = rs.getString("error_code");
        return new Job(rs.getString("id"), rs.getString("actor_id"), rs.getString("draft_id"), rs.getString("state"),
                json.read(rs.getString("input"), GenerationInput.class), rs.getInt("attempt"), lease == null ? null : lease.toInstant(),
                error == null ? null : Failure.Code.valueOf(error));
    }
}
