package dev.worldcup.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

class ReadinessControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ResourceLoader resources = mock(ResourceLoader.class);
    private final MockEnvironment environment = new MockEnvironment().withProperty("worldcup.worker.enabled", "true");

    private ReadinessController controller(boolean web) {
        when(resources.getResource("classpath:static/index.html"))
                .thenReturn(web ? new ByteArrayResource(new byte[]{1}) : new DescriptiveResource("missing"));
        return new ReadinessController(jdbc, environment, resources);
    }

    @Test void readyRequiresWebDatabaseAndExplicitEngineWithoutCallingProvider() {
        environment.setActiveProfiles("prod", "live");
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any())).thenReturn(true);
        var result = controller(true).ready();
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getBody()).isEqualTo(new ReadinessController.Readiness("READY", "dynamic-ai-world-cup"));
        verify(jdbc).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any());
    }

    @Test void devIsAllowedButNeverMixedWithProductionOrLive() {
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any())).thenReturn(true);
        environment.setActiveProfiles("dev");
        assertThat(controller(true).ready().getStatusCode().value()).isEqualTo(200);
        for (String[] profiles : new String[][]{{"dev", "prod"}, {"dev", "live"}, {"prod"}, {}}) {
            environment.setActiveProfiles(profiles);
            assertThat(controller(true).ready().getStatusCode().value()).isEqualTo(503);
        }
    }

    @Test void missingBundleOrDisabledWorkerIsNotReady() {
        environment.setActiveProfiles("live");
        assertThat(controller(false).ready().getStatusCode().value()).isEqualTo(503);
        environment.setProperty("worldcup.worker.enabled", "false");
        assertThat(controller(true).ready().getStatusCode().value()).isEqualTo(503);
        verifyNoInteractions(jdbc);
    }

    @Test void databaseFailureDoesNotExposeConnectionDetails() {
        environment.setActiveProfiles("live");
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any()))
                .thenThrow(new IllegalStateException("private database password"));
        var result = controller(true).ready();
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).isEqualTo(new ReadinessController.Readiness("NOT_READY", "dynamic-ai-world-cup"));
    }

    @Test void databaseProbeActuallyExecutesBoundedSelectAndClosesResources() throws Exception {
        environment.setActiveProfiles("live");
        var connection = mock(java.sql.Connection.class);
        var statement = mock(java.sql.Statement.class);
        var resultSet = mock(java.sql.ResultSet.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getInt(1)).thenReturn(1);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<Boolean>>any())).thenAnswer(invocation -> {
            ConnectionCallback<Boolean> callback = invocation.getArgument(0);
            return callback.doInConnection(connection);
        });
        assertThat(controller(true).ready().getStatusCode().value()).isEqualTo(200);
        verify(statement).setQueryTimeout(2);
        verify(statement).executeQuery("SELECT 1");
        verify(statement).close();
        verify(resultSet).close();
        verify(connection, never()).close();
    }
}
