package dev.worldcup.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class EngineTestConfiguration {
    @Bean @Primary ControlledEngine controlledEngine() { return new ControlledEngine(); }
}
