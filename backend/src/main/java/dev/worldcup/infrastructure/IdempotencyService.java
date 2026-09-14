package dev.worldcup.infrastructure;

import static dev.worldcup.shared.Failure.Code.*;
import dev.worldcup.identity.ActorService;
import dev.worldcup.shared.Failure;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The reservation, business mutation and saved response commit together, or all roll back. */
@Service
public class IdempotencyService {
    private final JdbcTemplate jdbc;
    private final JsonCodec json;
    private final TransactionTemplate transaction;
    public IdempotencyService(JdbcTemplate jdbc, JsonCodec json, TransactionTemplate transaction) {
        this.jdbc = jdbc; this.json = json; this.transaction = transaction;
    }
    public <T> T execute(String actor, String route, String key, Object request, Class<T> type, Supplier<T> action) {
        if (key == null || key.isBlank() || key.length() > 128) throw Failure.of(INVALID_INPUT);
        String hash = ActorService.hash(json.write(request));
        return transaction.execute(status -> {
            jdbc.update("""
                    INSERT INTO idempotency_request(actor_id, route_scope, key, request_hash)
                    VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING
                    """, actor, route, key, hash);
            var saved = jdbc.queryForMap("""
                    SELECT request_hash, response::text FROM idempotency_request
                    WHERE actor_id = ? AND route_scope = ? AND key = ? FOR UPDATE
                    """, actor, route, key);
            if (!hash.equals(saved.get("request_hash"))) throw Failure.of(IDEMPOTENCY_CONFLICT);
            if (saved.get("response") != null) return json.read((String) saved.get("response"), type);
            T response = action.get();
            jdbc.update("""
                    UPDATE idempotency_request SET response = ?::jsonb
                    WHERE actor_id = ? AND route_scope = ? AND key = ?
                    """, json.write(response), actor, route, key);
            return response;
        });
    }
}
