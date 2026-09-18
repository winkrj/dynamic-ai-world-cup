package dev.worldcup.infrastructure;

import dev.worldcup.generation.CandidateEngine.Preference;
import dev.worldcup.tournament.*;
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
public class JdbcTournamentRepository implements TournamentRepository {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    public JdbcTournamentRepository(JdbcTemplate jdbc, JsonCodec json) { this.jdbc = jdbc; this.json = json; }
    @Override public void saveSnapshot(String actor, String sourceDraft, String unit, BracketSnapshot snapshot) {
        jdbc.update("""
                INSERT INTO bracket_snapshot(id, source_draft_id, actor_id, candidate_unit, schema_version, payload, frozen_at)
                VALUES (?, ?, ?, ?, 1, ?::jsonb, ?)
                """, snapshot.snapshotId(), sourceDraft, actor, unit, json.write(snapshot), Timestamp.from(snapshot.frozenAt()));
    }
    @Override public Optional<BracketSnapshot> ownedSnapshot(String actor, String id) {
        return jdbc.query("""
                SELECT payload::text FROM bracket_snapshot b WHERE b.id = ? AND
                (b.actor_id = ? OR EXISTS (SELECT 1 FROM play_session s WHERE s.snapshot_id = b.id AND s.actor_id = ?))
                """, (rs, n) -> json.read(rs.getString(1), BracketSnapshot.class), id, actor, actor).stream().findFirst();
    }
    @Override public BracketSnapshot snapshot(String id) {
        return json.read(jdbc.queryForObject("SELECT payload::text FROM bracket_snapshot WHERE id = ?", String.class, id), BracketSnapshot.class);
    }
    @Override public Session createSession(String actor, String snapshot, String sourceDraft) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO play_session(id, actor_id, snapshot_id, source_draft_id, state) VALUES (?, ?, ?, ?, 'PLAYING')",
                id, actor, snapshot, sourceDraft);
        return new Session(id, actor, snapshot, "PLAYING", 0, null);
    }
    @Override public Optional<Session> originalSession(String draft) {
        return jdbc.query("SELECT * FROM play_session WHERE source_draft_id = ?", this::session, draft).stream().findFirst();
    }
    @Override public Optional<Session> ownedSession(String actor, String id, boolean lock) {
        return jdbc.query("SELECT * FROM play_session WHERE id = ? AND actor_id = ?" + (lock ? " FOR UPDATE" : ""),
                this::session, id, actor).stream().findFirst();
    }
    @Override public List<Selection> selections(String id) {
        return jdbc.query("SELECT * FROM pairwise_selection WHERE session_id = ? ORDER BY sequence", (rs, n) -> new Selection(
                rs.getString("event_id"), rs.getInt("sequence"), rs.getString("winner_id"),
                Selection.Reason.valueOf(rs.getString("reason")), rs.getLong("elapsed_ms")), id);
    }
    @Override public void append(String id, PlaySession.Result result) {
        for (var accepted : result.additions()) {
            var e = accepted.event();
            jdbc.update("""
                    INSERT INTO pairwise_selection(session_id, sequence, event_id, left_id, right_id, winner_id, reason, elapsed_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, e.sequence(), e.eventId(), accepted.leftId(), accepted.rightId(), e.winnerId(), e.reason().name(), e.elapsedMs());
        }
        jdbc.update("UPDATE play_session SET next_sequence = ?, state = ?, champion_id = ? WHERE id = ?",
                result.nextSequence(), result.status(), result.championId(), id);
    }
    @Override public List<Preference> recentDirectChoices(String actor, Instant since) {
        return jdbc.query("""
                SELECT b.candidate_unit, b.payload::text, e.winner_id, e.left_id, e.right_id, e.created_at
                FROM pairwise_selection e JOIN play_session s ON s.id = e.session_id
                JOIN bracket_snapshot b ON b.id = s.snapshot_id
                WHERE s.actor_id = ? AND e.reason = 'USER_SELECTED' AND e.created_at >= ?
                ORDER BY e.created_at DESC, e.session_id, e.sequence DESC LIMIT 50
                """, (rs, n) -> {
            var snapshot = json.read(rs.getString("payload"), BracketSnapshot.class);
            String winner = rs.getString("winner_id");
            String loser = winner.equals(rs.getString("left_id")) ? rs.getString("right_id") : rs.getString("left_id");
            String chosen = snapshot.candidates().stream().filter(c -> c.id().equals(winner)).findFirst().orElseThrow().name();
            String rejected = snapshot.candidates().stream().filter(c -> c.id().equals(loser)).findFirst().orElseThrow().name();
            return new Preference(rs.getString("candidate_unit"), chosen, rejected, rs.getTimestamp("created_at").toInstant());
        }, actor, Timestamp.from(since));
    }
    private Session session(ResultSet rs, int row) throws SQLException {
        return new Session(rs.getString("id"), rs.getString("actor_id"), rs.getString("snapshot_id"), rs.getString("state"),
                rs.getInt("next_sequence"), rs.getString("champion_id"));
    }
}
