package dev.worldcup.api;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Load actual application[-profile].properties, not merely the engine's bean annotations. */
class ServerProfilesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer()).withUserConfiguration(PublicAddress.class);
    @Test void explicitDevLoadsLocalHttpSettings() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PublicAddress.class).origin()).isEqualTo("http://127.0.0.1:5173");
            assertThat(context.getEnvironment().getProperty("worldcup.cookie-secure")).isEqualTo("false");
        });
    }
    @Test void mixedProfilesFailRegardlessOfOrdering() {
        for (String profiles : new String[]{"dev,prod", "prod,dev"}) {
            runner.withPropertyValues("spring.profiles.active=" + profiles).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage("Do not combine prod and dev profiles");
            });
        }
    }
    @Test void prodCannotDisableSecureCookies() {
        runner.withPropertyValues("spring.profiles.active=prod", "worldcup.cookie-secure=false").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("Production requires Secure cookies");
        });
    }
    @Test void prodLoadsSecureHttpsDefaults() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(PublicAddress.class).origin()).startsWith("https://");
            assertThat(context.getEnvironment().getProperty("worldcup.cookie-secure")).isEqualTo("true");
        });
    }
}
