package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.generation.*;
import dev.worldcup.identity.ActorService;
import dev.worldcup.support.PostgresSupport;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "worldcup.worker.lease-seconds=300")
class LiveLeaseTest extends PostgresSupport {
    @Autowired GenerationRepository jobs;
    @Autowired GenerationService generation;
    @Autowired ActorService actors;
    @Autowired TransactionTemplate transactions;
    @Autowired JdbcTemplate jdbc;
    @Test void liveLengthLeaseDoesNotRecoverAtTheOldSixtySecondBoundary() {
        jdbc.execute("TRUNCATE anonymous_actor, generation_quota CASCADE");
        var actor = actors.issue();
        generation.create(actor.actorId(), "127.0.0.1", new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul"));
        Instant now = Instant.parse("2026-09-15T00:00:00Z");
        transactions.executeWithoutResult(status -> {
            var job = jobs.claim(now).orElseThrow();
            assertThat(job.leaseUntil()).isEqualTo(now.plusSeconds(300));
            assertThat(jobs.expired(now.plusSeconds(61))).isEmpty();
            assertThat(jobs.expired(now.plusSeconds(300))).extracting(GenerationRepository.Job::id).containsExactly(job.id());
        });
    }
}
