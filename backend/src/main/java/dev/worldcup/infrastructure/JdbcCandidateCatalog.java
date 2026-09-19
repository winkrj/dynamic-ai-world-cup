package dev.worldcup.infrastructure;

import dev.worldcup.generation.GenerationInput;
import dev.worldcup.generation.catalog.CandidateCatalog;
import dev.worldcup.generation.catalog.CatalogEntry;
import dev.worldcup.generation.catalog.CatalogPreset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCandidateCatalog implements CandidateCatalog {
    private static final int MAX_CANDIDATES = 64;
    private final JdbcTemplate jdbc;

    public JdbcCandidateCatalog(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<CatalogEntry> candidates(String prompt, int limit) {
        int bounded = Math.clamp(limit, 0, MAX_CANDIDATES);
        if (bounded == 0) return List.of();
        String hint = normalize(prompt).toLowerCase(Locale.ROOT);
        // A matching word is retrieval assistance, never eligibility for a zero-call answer.
        boolean hobby = hint.contains("취미") || hint.contains("hobby");
        boolean date = hint.contains("데이트") || hint.contains("date");
        boolean dinner = hint.contains("저녁") || hint.contains("메뉴") || hint.contains("음식") || hint.contains("dinner");
        return List.copyOf(jdbc.query("""
                SELECT * FROM (
                    SELECT e.*, row_number() OVER (PARTITION BY topic ORDER BY ordinal, id) AS topic_position,
                        CASE WHEN (topic = 'hobby' AND ?) OR (topic = 'date' AND ?) OR (topic = 'dinner' AND ?)
                             THEN 0 ELSE 1 END AS topic_priority
                    FROM candidate_catalog_entry e
                    WHERE active AND time_independent AND provenance = 'AI_CURATED_EDITORIAL_V1'
                ) eligible ORDER BY topic_priority, topic_position, topic, id LIMIT ?
                """, this::entry, hobby, date, dinner, bounded));
    }

    @Override public Optional<CatalogPreset> preset(GenerationInput input) {
        var presets = jdbc.query("""
                SELECT p.id, p.topic, p.unit FROM candidate_catalog_preset p
                JOIN candidate_catalog_preset_alias a ON a.preset_id = p.id
                WHERE a.normalized_prompt = ? AND p.locale = ? AND p.timezone = ?
                  AND p.active AND p.provenance = 'AI_CURATED_EDITORIAL_V1'
                """, (rs, row) -> new PresetHeader(rs.getString("id"), rs.getString("topic"), rs.getString("unit")),
                normalize(input.prompt()), input.locale(), input.timezone());
        if (presets.size() != 1) return Optional.empty();
        PresetHeader preset = presets.getFirst();
        var entries = jdbc.query("""
                SELECT e.* FROM candidate_catalog_preset_entry m
                JOIN candidate_catalog_entry e ON e.id = m.candidate_id
                WHERE m.preset_id = ? AND e.topic = ? AND e.unit = ?
                  AND e.active AND e.time_independent AND e.provenance = 'AI_CURATED_EDITORIAL_V1'
                ORDER BY m.position, e.id LIMIT ?
                """, this::entry, preset.id(), preset.topic(), preset.unit(), MAX_CANDIDATES);
        if (entries.size() < input.size()) return Optional.empty();
        return Optional.of(new CatalogPreset(preset.id(), preset.unit(), entries));
    }

    private static String normalize(String prompt) {
        if (prompt == null) return "";
        // Do not discard words, punctuation, exclusions, budget, location or other conditions.
        return Normalizer.normalize(prompt, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip();
    }

    private CatalogEntry entry(ResultSet rs, int row) throws SQLException {
        var tags = rs.getArray("tags");
        try {
            return new CatalogEntry(rs.getString("id"), rs.getString("topic"), rs.getString("unit"),
                    rs.getString("family"), rs.getString("name"), List.of((String[]) tags.getArray()));
        } finally { tags.free(); }
    }

    private record PresetHeader(String id, String topic, String unit) {}
}
