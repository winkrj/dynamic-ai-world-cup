package dev.worldcup.infrastructure.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.engine.StagedCandidateEngine;
import dev.worldcup.infrastructure.DevelopmentCandidateEngine;
import dev.worldcup.infrastructure.UnavailableCandidateEngine;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class LiveEngineConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(UnavailableCandidateEngine.class, DevelopmentCandidateEngine.class, LiveEngineConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withPropertyValues("spring.profiles.active=live", "worldcup.ai.api-key=sk-test-only", "worldcup.worker.lease-seconds=300");
    @Test void liveHasExactlyOneRealEngineAndNoSyntheticFallback() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(CandidateEngine.class);
            assertThat(context.getBean(CandidateEngine.class)).isInstanceOf(StagedCandidateEngine.class);
        });
    }
    @Test void mixedDevLiveProfileIsRejected() { runner.withPropertyValues("spring.profiles.active=dev,live").run(c -> assertThat(c).hasFailed()); }
    @Test void missingKeyIsRejected() { runner.withPropertyValues("worldcup.ai.api-key=").run(c -> assertThat(c).hasFailed()); }
    @Test void shorterLeaseThanOperationIsRejected() { runner.withPropertyValues("worldcup.worker.lease-seconds=60").run(c -> assertThat(c).hasFailed()); }
}
