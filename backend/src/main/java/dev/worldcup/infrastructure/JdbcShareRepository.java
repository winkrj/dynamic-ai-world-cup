package dev.worldcup.infrastructure;

import dev.worldcup.sharing.ShareRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcShareRepository implements ShareRepository {
    private final JdbcTemplate jdbc;
    public JdbcShareRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public Optional<Link> find(String token) {
        return jdbc.query("SELECT * FROM share_link WHERE token = ?", this::link, token).stream().findFirst();
    }
    @Override public Optional<Link> forSession(String session) {
        return jdbc.query("SELECT * FROM share_link WHERE creator_session_id = ?", this::link, session).stream().findFirst();
    }
    @Override public void insert(Link link) {
        jdbc.update("INSERT INTO share_link(token, snapshot_id, creator_session_id, champion_id) VALUES (?, ?, ?, ?)",
                link.token(), link.snapshotId(), link.creatorSessionId(), link.championId());
    }
    private Link link(ResultSet rs, int row) throws SQLException {
        return new Link(rs.getString("token"), rs.getString("snapshot_id"), rs.getString("creator_session_id"), rs.getString("champion_id"));
    }
}
