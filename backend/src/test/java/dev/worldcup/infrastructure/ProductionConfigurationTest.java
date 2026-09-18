package dev.worldcup.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationTest {
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("worldcup.public-origin", "https://worldcup.example")
            .withProperty("worldcup.cookie-secure", "true")
            .withProperty("spring.datasource.url", "jdbc:postgresql://database/worldcup")
            .withProperty("spring.datasource.username", "worldcup")
            .withProperty("spring.datasource.password", "test-only-not-a-secret")
            .withProperty("worldcup.worker.enabled", "true")
            .withProperty("worldcup.retention.enabled", "true");
    private final ResourceLoader resources = mock(ResourceLoader.class);

    private void validate() { new ProductionConfiguration(environment, resources); }
    private void configured() {
        environment.setActiveProfiles("prod", "live");
        when(resources.getResource("classpath:static/index.html")).thenReturn(new ByteArrayResource(new byte[]{1}));
    }

    @Test void explicitlyConfiguredCombinedApplicationIsAcceptedWithoutProviderCalls() {
        configured();
        assertThatCode(this::validate).doesNotThrowAnyException();
    }

    @Test void missingLiveOrMixedDevFails() {
        configured();
        for (String[] profiles : new String[][]{{"prod"}, {"prod", "live", "dev"}}) {
            environment.setActiveProfiles(profiles);
            assertThatIllegalArgumentException().isThrownBy(this::validate).withMessage("Production requires live without dev");
        }
    }

    @Test void localhostOrInsecureOriginAndCookiesFail() {
        configured();
        for (String origin : new String[]{"https://localhost", "https://127.0.0.1", "https://[::1]", "http://worldcup.example"}) {
            environment.setProperty("worldcup.public-origin", origin);
            assertThatIllegalArgumentException().isThrownBy(this::validate);
        }
        environment.setProperty("worldcup.public-origin", "https://worldcup.example");
        environment.setProperty("worldcup.cookie-secure", "false");
        assertThatIllegalArgumentException().isThrownBy(this::validate);
    }

    @Test void localOrMissingDatabaseCredentialsFailWithoutPrintingSecrets() {
        configured();
        for (String password : new String[]{"", "worldcup-local-only"}) {
            environment.setProperty("spring.datasource.password", password);
            assertThatIllegalArgumentException().isThrownBy(this::validate)
                    .withMessage("Production requires explicit PostgreSQL credentials, not local defaults");
        }
        environment.setProperty("spring.datasource.password", "test-only-not-a-secret");
        environment.setProperty("spring.datasource.url", "jdbc:h2:mem:wrong");
        assertThatIllegalArgumentException().isThrownBy(this::validate);
    }

    @Test void missingBundleOrBackgroundWorkersFail() {
        configured();
        environment.setProperty("worldcup.worker.enabled", "false");
        assertThatIllegalArgumentException().isThrownBy(this::validate);
        environment.setProperty("worldcup.worker.enabled", "true");
        environment.setProperty("worldcup.retention.enabled", "false");
        assertThatIllegalArgumentException().isThrownBy(this::validate);
        environment.setProperty("worldcup.retention.enabled", "true");
        when(resources.getResource("classpath:static/index.html")).thenReturn(new DescriptiveResource("missing"));
        assertThatIllegalArgumentException().isThrownBy(this::validate)
                .withMessage("Production requires the combined frontend application artifact");
    }
}
