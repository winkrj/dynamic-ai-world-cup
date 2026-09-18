package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.engine.EngineModels.CallContext;
import dev.worldcup.infrastructure.JsonCodec;
import dev.worldcup.support.PostgresSupport;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate explicit approval required. Ordinary verification skips this paid one-call diagnostic. */
@EnabledIfEnvironmentVariable(named = "CANDIDATE_INTERPRETATION_DIAGNOSTIC", matches = "true")
@EnabledIfEnvironmentVariable(named = "CANDIDATE_LIVE_TEST", matches = "false")
@SpringBootTest
class LiveInterpretationDiagnosticTest extends PostgresSupport {
    private static final Path ROOT = Path.of("../reports/local/live-engine");
    private static final String SOURCE_RUN = "2026-09-18T07-33-51.503430Z-size32";
    private static final String SOURCE_SHA = "eef7e0f3a058987eb9b6f7890528ae47f8a5d3a195ceca9b9eb3e3f7e2b6d771";
    private static final BigDecimal RESERVE = new BigDecimal("0.50");
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired JsonCodec json;

    @Test void reviewsOnlyTheRecordedV20AllocationWithTheV21Reviewer() throws Exception {
        assertThat(OpenAiResponsesClient.PROMPT_VERSION).isEqualTo("ce002-v21-request-exclusion-strength");
        byte[] source = Files.readAllBytes(ROOT.resolve(SOURCE_RUN).resolve("exchanges.private.json"));
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source))).isEqualTo(SOURCE_SHA);
        var recorded = RecordedAllocation.read(new String(source, java.nio.charset.StandardCharsets.UTF_8), json);
        var accountedBefore = RecordedAllocation.accountedTotal(ROOT, json);
        // Fail closed if the known history is missing or another experiment changed the reviewed baseline.
        assertThat(accountedBefore).isEqualByComparingTo("4.8498083");
        assertThat(accountedBefore.add(RESERVE)).isLessThanOrEqualTo(new BigDecimal("6"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class)).isZero();
        var exchanges = new ArrayList<OpenAiResponsesClient.Exchange>();
        var clock = Clock.systemUTC();
        var client = new OpenAiResponsesClient(URI.create("https://api.openai.com/v1/responses"),
                System.getenv("OPENAI_API_KEY"), new JdbcProviderCallLedger(jdbc, transactions, RESERVE), clock,
                Duration.ofSeconds(90), exchanges::add);
        var stages = new OpenAiCandidateStages(client, clock, "gpt-5.6-terra", "gpt-5.6-terra");
        // Atomic, fixed directory claim: never remove/reuse it to turn one approval into another attempt.
        Path output = ROOT.resolve("interpretation-v20-allocate-v21-once");
        Files.createDirectory(output);
        String jobId = UUID.randomUUID().toString();
        RecordedAllocation.writeLedger(output.resolve("ledger.json"), json.write(List.of(Map.of("id", jobId,
                "accounted_usd", RESERVE, "state", "DIAGNOSTIC_ATTEMPT_UNCONFIRMED"))));
        Files.writeString(output.resolve("run.json"), json.write(Map.of("sourceRun", SOURCE_RUN, "sourceSha256", SOURCE_SHA,
                "startedAt", clock.instant(), "promptVersion", OpenAiResponsesClient.PROMPT_VERSION,
                "accountedBeforeUsd", accountedBefore, "reservedUsd", RESERVE,
                "scope", "one recorded allocation diagnostic; not seed generation, preview or human quality approval")));
        try {
            var result = recorded.reviewOnce(stages, new CallContext(jobId, 1, "ALLOCATE_DIAGNOSTIC", clock.instant().plusSeconds(90)));
            Files.writeString(output.resolve("review.private.json"), json.write(result));
            boolean detected = recorded.reportsExerciseExclusionMismatch(result.value());
            Files.writeString(output.resolve("diagnostic.json"), json.write(Map.of("reportedExclusionMismatch", detected,
                    "humanReviewRequired", true, "candidateQualityApproved", false)));
            assertThat(exchanges).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM provider_call", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM generation_job", Integer.class)).isZero();
            assertThat(detected).as("Reviewer must report the original exclusion mismatch, not merely a candidate defect").isTrue();
        } finally {
            // Write accounting first. An interrupted/uncertain attempt retains the conservative reservation.
            var rows = jdbc.queryForList("SELECT * FROM provider_call WHERE job_id = ? ORDER BY created_at, id", jobId);
            if (!rows.isEmpty()) RecordedAllocation.writeLedger(output.resolve("ledger.json"), json.write(rows));
            Files.writeString(output.resolve("exchanges.private.json"), json.write(exchanges));
            System.out.println("Interpretation diagnostic artifacts: " + output.toAbsolutePath().normalize());
        }
    }
}
