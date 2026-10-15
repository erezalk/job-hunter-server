package dev.erezalkalay.jobhunter;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton-container pattern: the container is started once, manually, in a
 * static initializer rather than via {@code @Testcontainers}/{@code @Container}.
 * That JUnit5 extension manages container lifecycle per concrete test class,
 * which stops and restarts this "shared" static field between subclasses
 * even though it is a single static field — leaving Spring's cached
 * ApplicationContext (correctly reused across subclasses with identical
 * config) pointed at a datasource whose container has already been torn
 * down. Starting it once here and never stopping it (Ryuk reaps it at JVM
 * exit) keeps container and cached context in sync.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
