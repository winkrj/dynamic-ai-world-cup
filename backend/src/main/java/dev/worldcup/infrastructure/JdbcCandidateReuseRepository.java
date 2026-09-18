package dev.worldcup.infrastructure;

import dev.worldcup.generation.reuse.CandidateReuseRepository;
import dev.worldcup.generation.reuse.ValidationCertificate;
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
public class JdbcCandidateReuseRepository implements CandidateReuseRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public JdbcCandidateReuseRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }
    @Override public List<StoredSet> approved(String contextHash, Instant now) {
        return jdbc.query("""
                SELECT * FROM candidate_reuse_set WHERE context_hash = ? AND state = 'APPROVED' AND expires_at > ?
                ORDER BY approved_at DESC, id LIMIT 100
                """, this::set, contextHash, Timestamp.from(now));
    }
    @Override public Optional<StoredSet> get(String id, boolean lock) {
        return jdbc.query("SELECT * FROM candidate_reuse_set WHERE id = ?" + (lock ? " FOR UPDATE" : ""), this::set, id).stream().findFirst();
    }
    @Override public Optional<StoredSet> forDraft(String actorId, String draftId) {
        return jdbc.query("""
                SELECT s.* FROM generation_job j
                LEFT JOIN candidate_reuse_use u ON u.job_id = j.id AND u.attempt = j.attempt
                JOIN candidate_reuse_set s ON (s.source_job_id = j.id AND s.source_attempt = j.attempt) OR s.id = u.set_id
                WHERE j.actor_id = ? AND j.draft_id = ? AND j.state = 'READY'
                ORDER BY j.finished_at DESC, j.id DESC LIMIT 1
                """, this::set, actorId, draftId).stream().findFirst();
    }
    @Override public List<StoredSet> pending() {
        return jdbc.query("SELECT * FROM candidate_reuse_set WHERE state = 'PENDING' ORDER BY created_at, id LIMIT 100", this::set);
    }
    @Override public void stage(StoredSet set) {
        jdbc.update("""
                INSERT INTO candidate_reuse_set(id, context_hash, membership_hash, policy_version, source_job_id, source_attempt,
                    state, public_title, provider_version, validator_version, certificate, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (source_job_id, source_attempt) DO NOTHING
                """, set.id(), set.contextHash(), set.membershipHash(), set.policyVersion(), set.sourceJobId(), set.sourceAttempt(),
                set.publicTitle(), set.providerVersion(), set.validatorVersion(), json.write(set.certificate()),
                Timestamp.from(set.createdAt()), Timestamp.from(set.createdAt()));
    }
    @Override public void approve(String id, Approval approval, Instant now) {
        int changed = jdbc.update("""
                UPDATE candidate_reuse_set SET state = 'APPROVED', quality_approved = true, public_safe = true,
                    time_independent = true, approved_at = ?, expires_at = ?, updated_at = ? WHERE id = ? AND state = 'PENDING'
                """, Timestamp.from(now), Timestamp.from(approval.expiresAt()), Timestamp.from(now), id);
        if (changed != 1) throw new IllegalStateException("Lost pending reuse review");
        jdbc.update("""
                INSERT INTO candidate_reuse_review(id, set_id, action, operator_id, quality_review, public_safety_review,
                    time_independent, expires_at, created_at) VALUES (?, ?, 'APPROVED', ?, ?, ?, true, ?, ?)
                """, UUID.randomUUID().toString(), id, approval.operatorId(), approval.qualityReview(), approval.publicSafetyReview(),
                Timestamp.from(approval.expiresAt()), Timestamp.from(now));
    }
    @Override public void transition(String id, State state, String operatorId, String reason, Instant now) {
        jdbc.update("UPDATE candidate_reuse_set SET state = ?, updated_at = ? WHERE id = ?", state.name(), Timestamp.from(now), id);
        jdbc.update("""
                INSERT INTO candidate_reuse_review(id, set_id, action, operator_id, reason, created_at) VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), id, state.name(), operatorId, reason, Timestamp.from(now));
    }
    @Override public void recordUse(String setId, String jobId, int attempt, Instant now) {
        jdbc.update("INSERT INTO candidate_reuse_use(job_id, attempt, set_id, created_at) VALUES (?, ?, ?, ?)",
                jobId, attempt, setId, Timestamp.from(now));
    }
    private StoredSet set(ResultSet rs, int row) throws SQLException {
        Timestamp expiry = rs.getTimestamp("expires_at");
        return new StoredSet(rs.getString("id"), rs.getString("context_hash"), rs.getString("membership_hash"), rs.getString("policy_version"),
                rs.getString("source_job_id"), rs.getInt("source_attempt"), State.valueOf(rs.getString("state")),
                rs.getString("public_title"), rs.getString("provider_version"), rs.getString("validator_version"),
                readCertificate(rs.getString("certificate")), rs.getTimestamp("created_at").toInstant(),
                expiry == null ? null : expiry.toInstant(), rs.getBoolean("quality_approved"), rs.getBoolean("public_safe"), rs.getBoolean("time_independent"));
    }
    private ValidationCertificate readCertificate(String value) {
        try { return json.read(value, ValidationCertificate.class); }
        catch (RuntimeException invalid) { return null; } // Corrupt/obsolete certificates are misses, never invented evidence.
    }
}
