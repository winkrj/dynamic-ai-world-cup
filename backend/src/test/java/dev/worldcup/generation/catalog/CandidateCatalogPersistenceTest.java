package dev.worldcup.generation.catalog;

import static org.assertj.core.api.Assertions.*;

import dev.worldcup.generation.GenerationInput;
import dev.worldcup.support.PostgresSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** Catalog lifecycle in PostgresSupport's disposable DB; no provider calls or production data. */
@SpringBootTest
@Transactional
class CandidateCatalogPersistenceTest extends PostgresSupport {
    @Autowired CandidateCatalog catalog;
    @Autowired JdbcTemplate jdbc;

    private GenerationInput input(String prompt, int size) {
        return new GenerationInput(prompt, size, "ko-KR", "Asia/Seoul");
    }

    @Test void migrationSeedsDistinctGenericEditorialCandidatesWithoutHumanApprovalClaims() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_catalog_entry", Integer.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_catalog_entry WHERE topic = 'hobby'", Integer.class)).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_catalog_entry WHERE topic = 'date'", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_catalog_entry WHERE topic = 'dinner'", Integer.class)).isEqualTo(16);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM candidate_catalog_entry
                WHERE active AND time_independent AND NOT human_rated AND provenance = 'AI_CURATED_EDITORIAL_V1'
                """, Integer.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT (topic, family)) FROM candidate_catalog_entry", Integer.class)).isEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success", Integer.class)).isEqualTo(1);
    }

    @Test void retrievalIsBoundedAndMixesTopicsWhenNoHintMatches() {
        assertThat(catalog.candidates("합성 미분류 요청", -1)).isEmpty();
        assertThat(catalog.candidates("합성 미분류 요청", 0)).isEmpty();
        assertThat(catalog.candidates("합성 미분류 요청", 1)).hasSize(1);
        assertThat(catalog.candidates("합성 미분류 요청", 3)).extracting(CatalogEntry::topic)
                .containsExactlyInAnyOrder("hobby", "date", "dinner");
        assertThat(catalog.candidates(null, 10)).hasSize(10);
        assertThat(catalog.candidates("합성 미분류 요청", Integer.MAX_VALUE)).hasSize(64);
    }

    @Test void topicWordsPrioritizeSuggestionsButNeverCertifyConditions() {
        assertThat(catalog.candidates("취미 추천해줘", 16)).hasSize(16).allMatch(e -> e.topic().equals("hobby"));
        assertThat(catalog.candidates("데이트 활동 추천해줘", 16)).hasSize(16).allMatch(e -> e.topic().equals("date"));
        assertThat(catalog.candidates("저녁 메뉴 추천해줘", 16)).hasSize(16).allMatch(e -> e.topic().equals("dinner"));
        assertThat(catalog.candidates("운동은 싫고 혼자 할 취미 추천해줘", 32))
                .extracting(CatalogEntry::name).contains("달리기"); // Retrieval is NOT constraint filtering.
        assertThat(catalog.preset(input("운동은 싫고 혼자 할 취미 추천해줘", 16))).isEmpty();
    }

    @Test void exactPresetMatchingAllowsOnlyWhitespaceNormalizationAndEnoughActiveRows() {
        var hobby = catalog.preset(input(" \n취미　추천해줘\t", 32)).orElseThrow();
        assertThat(hobby.id()).isEqualTo("hobby-basic");
        assertThat(hobby.candidates()).hasSize(32).allMatch(e -> e.unit().equals(hobby.unit()));
        assertThat(catalog.preset(input("취미 추천해줘", 8)).orElseThrow().candidates()).hasSize(32);
        assertThat(catalog.preset(input("데이트 활동 추천해줘", 16)).orElseThrow().candidates()).hasSize(16);
        assertThat(catalog.preset(input("저녁 메뉴 추천해줘", 8)).orElseThrow().id()).isEqualTo("dinner-basic");
        assertThat(catalog.preset(input("데이트 활동 추천해줘", 32))).isEmpty();
        assertThat(catalog.preset(input("저녁 메뉴 추천해줘", 32))).isEmpty();
    }

    @Test void appendedConditionsPunctuationAndUnrecognizedTimezoneNeverMatchPresets() {
        for (String prompt : List.of("취미 추천해줘!", "취미 추천해줘 월 10만원 이하", "취미 추천해줘 운동 말고",
                "혼자 할 취미 추천해줘", "집에서 할 취미 추천해줘", "서울 데이트 활동 추천해줘",
                "데이트 활동 추천해줘 주말에", "저녁 메뉴 추천해줘 채식으로", "취미추천해줘")) {
            assertThat(catalog.preset(input(prompt, 16))).as(prompt).isEmpty();
        }
        assertThat(catalog.preset(new GenerationInput("취미 추천해줘", 16, "ko-KR", "UTC"))).isEmpty();
    }

    @Test void revocationExcludesEntriesAndInsufficientPresetBecomesMiss() {
        jdbc.update("UPDATE candidate_catalog_entry SET active = false WHERE id = 'hobby-reading'");
        assertThat(catalog.candidates("취미", 64)).extracting(CatalogEntry::id).doesNotContain("hobby-reading");
        assertThat(catalog.preset(input("취미 추천해줘", 32))).isEmpty();
        assertThat(catalog.preset(input("취미 추천해줘", 16)).orElseThrow().candidates())
                .hasSize(31).extracting(CatalogEntry::id).doesNotContain("hobby-reading");
    }

    @Test void unknownProvenanceAndTimeDependentEntriesAreNotEligibleEvenWhenActive() {
        jdbc.update("UPDATE candidate_catalog_entry SET provenance = 'UNREVIEWED_GENERATION' WHERE id = 'hobby-reading'");
        jdbc.update("UPDATE candidate_catalog_entry SET time_independent = false WHERE id = 'hobby-drawing'");
        assertThat(catalog.candidates("취미", 64)).hasSize(62).extracting(CatalogEntry::id)
                .doesNotContain("hobby-reading", "hobby-drawing");
        assertThat(catalog.preset(input("취미 추천해줘", 32))).isEmpty();
        assertThat(catalog.preset(input("취미 추천해줘", 16)).orElseThrow().candidates()).hasSize(30);
    }

    @Test void revokedAndUnrecognizedPresetProvenanceAreMisses() {
        jdbc.update("UPDATE candidate_catalog_preset SET active = false WHERE id = 'date-basic'");
        jdbc.update("UPDATE candidate_catalog_preset SET provenance = 'UNREVIEWED_GENERATION' WHERE id = 'dinner-basic'");
        assertThat(catalog.preset(input("데이트 활동 추천해줘", 8))).isEmpty();
        assertThat(catalog.preset(input("저녁 메뉴 추천해줘", 8))).isEmpty();
        assertThat(catalog.candidates("데이트", 16)).hasSize(16); // Preset and entry lifecycles are separate.
    }

    @Test void presetDoesNotIncludeCrossTopicOrCrossUnitMappings() {
        jdbc.update("INSERT INTO candidate_catalog_preset_entry VALUES ('date-basic', 'hobby-reading', 17)");
        jdbc.update("UPDATE candidate_catalog_entry SET unit = '합성 다른 단위' WHERE id = 'date-cinema'");
        assertThat(catalog.preset(input("데이트 활동 추천해줘", 16))).isEmpty();
        var entries = catalog.preset(input("데이트 활동 추천해줘", 8)).orElseThrow().candidates();
        assertThat(entries).hasSize(15).extracting(CatalogEntry::id).doesNotContain("hobby-reading", "date-cinema");
    }

    @Test void migrationDoesNotAlterFrozenSnapshotOrReuseEvidenceMechanisms() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_trigger WHERE NOT tgisinternal
                  AND tgname IN ('immutable_snapshot', 'immutable_reuse_certificate') AND tgenabled = 'O'
                """, Integer.class)).isEqualTo(2);
        long snapshots = jdbc.queryForObject("SELECT count(*) FROM bracket_snapshot", Long.class);
        long certificates = jdbc.queryForObject("SELECT count(*) FROM candidate_reuse_set", Long.class);
        catalog.candidates("취미", 64);
        catalog.preset(input("취미 추천해줘", 16));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bracket_snapshot", Long.class)).isEqualTo(snapshots);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_reuse_set", Long.class)).isEqualTo(certificates);
    }

    @Test void valueObjectsDefensivelyCopyCollections() {
        var tags = new ArrayList<>(List.of("합성"));
        var entry = new CatalogEntry("fixture", "fixture", "합성 단위", "fixture", "합성 후보", tags);
        tags.clear();
        assertThat(entry.tags()).containsExactly("합성");
        var entries = new ArrayList<>(List.of(entry));
        var preset = new CatalogPreset("fixture", "합성 단위", entries);
        entries.clear();
        assertThat(preset.candidates()).containsExactly(entry);
        assertThatThrownBy(() -> entry.tags().add("변경")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> preset.candidates().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
}
