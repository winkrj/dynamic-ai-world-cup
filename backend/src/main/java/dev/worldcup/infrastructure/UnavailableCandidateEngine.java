package dev.worldcup.infrastructure;

import dev.worldcup.generation.CandidateEngine;
import dev.worldcup.generation.GenerationInput;
import dev.worldcup.shared.Failure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class UnavailableCandidateEngine {
    @Bean @ConditionalOnMissingBean(CandidateEngine.class)
    CandidateEngine unavailableEngine() {
        return (GenerationInput input, CandidateEngine.Context context) -> { throw Failure.of(Failure.Code.PROVIDER_UNAVAILABLE); };
    }
}
