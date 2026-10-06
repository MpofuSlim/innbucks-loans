package zw.co.innbucks.loans.it;

import org.hibernate.resource.jdbc.spi.StatementInspector;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records every SQL statement Hibernate prepares, as it will send it, for a test that must see the SQL a derived or
 * Criteria query really produces. Installed by an IT through {@code hibernate.session_factory.statement_inspector};
 * shared by the whole JVM, so a test clears it, runs one thing, and reads it.
 */
public class CapturedSql implements StatementInspector {

    private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

    @Override
    public String inspect(String sql) {
        STATEMENTS.add(sql);
        return sql;
    }

    public static void clear() {
        STATEMENTS.clear();
    }

    public static List<String> statements() {
        return List.copyOf(STATEMENTS);
    }
}
