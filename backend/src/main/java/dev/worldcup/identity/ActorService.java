package dev.worldcup.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ActorService {
    public record Issued(String actorId, String token) {}
    private final JdbcTemplate jdbc;
    private final SecureRandom random;
    public ActorService(JdbcTemplate jdbc, SecureRandom random) { this.jdbc = jdbc; this.random = random; }
    public Optional<String> find(String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return Optional.empty();
        return jdbc.query("SELECT id FROM anonymous_actor WHERE token_hash = ?", (rs, n) -> rs.getString(1), hash(token)).stream().findFirst();
    }
    public Issued issue() {
        String token = token();
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO anonymous_actor(id, token_hash) VALUES (?, ?)", id, hash(token));
        return new Issued(id, token);
    }
    public String token() {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
