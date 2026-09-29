package zw.co.reikan.loans.core.user;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import zw.co.reikan.loans.core.StartupTask;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The retired-group migration runs before JPA, on a bare connection. Its SQL is exercised against
 * PostgreSQL by hand (see the PR); these pin the parts that decide whether the application starts.
 */
class LegacyDataMigrationTest {

    private final DataSource dataSource = mock(DataSource.class);
    private final Connection connection = mock(Connection.class);
    private final DatabaseMetaData metaData = mock(DatabaseMetaData.class);

    @Test
    @DisplayName("a fresh database (no tables yet) is left alone: nothing is queried or changed")
    void freshDatabaseIsANoOp() throws Exception {
        givenTables(false);

        new LegacyDataMigration(dataSource).migrate();

        verify(connection, never()).prepareStatement(anyString());
        verify(connection, never()).createStatement();
        verify(connection).commit();
    }

    @Test
    @DisplayName("a failure rolls everything back and refuses to start, naming the retired groups")
    void failureRefusesToStart() throws Exception {
        givenTables(true);
        when(connection.prepareStatement(anyString())).thenThrow(new SQLException("permission denied"));

        assertThatThrownBy(() -> new LegacyDataMigration(dataSource).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to start")
                .hasMessageContaining("SUB_AGENTS");
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test
    @DisplayName("no connection at all refuses to start too")
    void noConnectionRefusesToStart() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection refused"));

        assertThatThrownBy(() -> new LegacyDataMigration(dataSource).migrate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("every retired group is a name the enum no longer has, and every successor one it does")
    void retiredGroupsAreGoneFromTheEnum() {
        assertThat(LegacyDataMigration.REMOVED_GROUPS).noneMatch(LegacyDataMigrationTest::isCurrentGroup);
        assertThat(LegacyDataMigration.RENAMED_GROUPS.keySet()).noneMatch(LegacyDataMigrationTest::isCurrentGroup);
        assertThat(LegacyDataMigration.RENAMED_GROUPS.values()).allMatch(LegacyDataMigrationTest::isCurrentGroup);
    }

    @Test
    @DisplayName("the BulkIT commission groups are renamed to exactly the names the startup task seeds")
    void commissionGroupsRenameToTheSeededNames() {
        assertThat(LegacyDataMigration.RENAMED_COMMISSION_GROUPS).containsOnlyKeys(
                "80-20-Favouring-BulkIT", "100-Favouring-BulkIT", "Default-BulkIT");
        assertThat(LegacyDataMigration.RENAMED_COMMISSION_GROUPS.values()).containsExactlyInAnyOrder(
                StartupTask.EIGHTY_TWENTY, StartupTask.FAVOURING_INNBUCKS, StartupTask.ZERO_BASED_DEFAULT);
    }

    private static boolean isCurrentGroup(String name) {
        return Arrays.stream(UserGroup.values()).anyMatch(group -> group.name().equals(name));
    }

    private void givenTables(boolean exists) throws SQLException {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getMetaData()).thenReturn(metaData);
        ResultSet tables = mock(ResultSet.class);
        when(tables.next()).thenReturn(exists);
        when(metaData.getTables(any(), any(), anyString(), any())).thenReturn(tables);
    }
}
