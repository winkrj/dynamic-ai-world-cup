package dev.worldcup.infrastructure.ai;

import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.engine.StagedCandidateEngine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@Profile("live")
public class LiveEngineConfiguration {
    @Bean ProviderCallLedger providerCallLedger(JdbcTemplate jdbc, PlatformTransactionManager transactions,
            @Value("${worldcup.ai.budget-usd:0}") BigDecimal budget) {
        return new JdbcProviderCallLedger(jdbc, new TransactionTemplate(transactions), budget);
    }
    @Bean OpenAiResponsesClient openAiResponsesClient(ProviderCallLedger ledger, Clock clock,
            @Value("${worldcup.ai.api-key:}") String key,
            @Value("${worldcup.ai.attempt-timeout-seconds:90}") int attemptSeconds) {
        return new OpenAiResponsesClient(key, ledger, clock, Duration.ofSeconds(attemptSeconds));
    }
    @Bean CandidateEngine liveCandidateEngine(OpenAiResponsesClient client, Clock clock, Environment environment,
            @Value("${worldcup.ai.generation-model:gpt-5.6-terra}") String generationModel,
            @Value("${worldcup.ai.review-model:gpt-5.6-terra}") String reviewModel,
            @Value("${worldcup.ai.attempt-timeout-seconds:90}") int attemptSeconds,
            @Value("${worldcup.ai.operation-timeout-seconds:280}") int operationSeconds,
            @Value("${worldcup.worker.lease-seconds:60}") int leaseSeconds) {
        if (environment.acceptsProfiles(Profiles.of("dev")) || attemptSeconds < 1 || attemptSeconds > 90
                || operationSeconds < attemptSeconds || operationSeconds > 280 || leaseSeconds < operationSeconds + 5) {
            throw new IllegalArgumentException("Live engine requires isolated profile and consistent bounded deadlines");
        }
        return new StagedCandidateEngine(new OpenAiCandidateStages(client, clock, generationModel, reviewModel), clock, Duration.ofSeconds(operationSeconds));
    }
}
