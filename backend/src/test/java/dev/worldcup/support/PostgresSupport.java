package dev.worldcup.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Real PostgreSQL in an isolated disposable container; never connect tests to a developer database. */
public abstract class PostgresSupport {
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    static { POSTGRES.start(); }
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("worldcup.worker.enabled", () -> "false");
        properties.add("worldcup.retention.enabled", () -> "false");
        properties.add("worldcup.public-origin", () -> "https://worldcup.example");
    }
}
