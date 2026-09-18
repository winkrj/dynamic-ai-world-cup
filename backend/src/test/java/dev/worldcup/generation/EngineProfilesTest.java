package dev.worldcup.generation;

import static org.assertj.core.api.Assertions.*;
import dev.worldcup.infrastructure.DevelopmentCandidateEngine;
import dev.worldcup.infrastructure.UnavailableCandidateEngine;
import dev.worldcup.shared.Failure;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EngineProfilesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DevelopmentCandidateEngine.class, UnavailableCandidateEngine.class);
    @Test void defaultEngineFailsClosed() { checkUnavailable(runner); }
    @Test void prodNeverUsesSyntheticCandidatesEvenWithDevSelected() {
        checkUnavailable(runner.withPropertyValues("spring.profiles.active=dev,prod"));
    }
    @Test void explicitDevModeLabelsSyntheticData() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasSingleBean(CandidateEngine.class);
            var result = context.getBean(CandidateEngine.class).generate(input(), engineContext());
            assertThat(result.publicTitle()).contains("개발용");
            assertThat(result.candidates().candidates()).allSatisfy(c -> assertThat(c.name()).contains("개발용"));
        });
    }
    private void checkUnavailable(ApplicationContextRunner configuration) {
        configuration.run(context -> {
            assertThat(context).hasSingleBean(CandidateEngine.class);
            assertThatThrownBy(() -> context.getBean(CandidateEngine.class).generate(input(), engineContext()))
                    .isInstanceOfSatisfying(Failure.class, error -> assertThat(error.code()).isEqualTo(Failure.Code.PROVIDER_UNAVAILABLE));
        });
    }
    private GenerationInput input() { return new GenerationInput("취미", 8, "ko-KR", "Asia/Seoul"); }
    private CandidateEngine.Context engineContext() { return new CandidateEngine.Context("test", 1, Instant.now().plusSeconds(60), List.of()); }
}
