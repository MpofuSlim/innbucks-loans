package zw.co.innbucks.loans.it;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * The Postgres the *IT classes run against: a {@code postgres:16-alpine} container where Docker is available (CI), or
 * the server {@code LOANS_IT_POSTGRES} names as {@code host:port} (user and password {@code LOANS_IT_POSTGRES_USER} /
 * {@code _PASSWORD}, default postgres/postgres), for a machine with Postgres and no Docker. Each test class gets a
 * database of its own, created empty, so Flyway builds it from V1 exactly as on a new cell.
 */
public final class ItPostgres {

    private static final String EXTERNAL = System.getenv("LOANS_IT_POSTGRES");
    private static PostgreSQLContainer container;

    private ItPostgres() {
    }

    /** For {@code @EnabledIf}: a server named, or Docker to start one. */
    public static boolean available() {
        if (EXTERNAL != null && !EXTERNAL.isBlank()) {
            return true;
        }
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** A new, empty database; its JDBC URL. */
    public static synchronized String createDatabase(String prefix) {
        String name = prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection admin = DriverManager.getConnection(url("postgres"), user(), password());
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not create test database " + name, ex);
        }
        return url(name);
    }

    public static String user() {
        return EXTERNAL == null ? server().getUsername() : env("LOANS_IT_POSTGRES_USER", "postgres");
    }

    public static String password() {
        return EXTERNAL == null ? server().getPassword() : env("LOANS_IT_POSTGRES_PASSWORD", "postgres");
    }

    private static String url(String database) {
        String hostPort = EXTERNAL != null ? EXTERNAL.trim()
                : server().getHost() + ":" + server().getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
        return "jdbc:postgresql://" + hostPort + "/" + database;
    }

    @SuppressWarnings("resource")
    private static synchronized PostgreSQLContainer server() {
        if (container == null) {
            container = new PostgreSQLContainer("postgres:16-alpine").withDatabaseName("postgres");
            container.start();
        }
        return container;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
