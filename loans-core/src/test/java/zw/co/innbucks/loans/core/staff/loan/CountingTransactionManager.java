package zw.co.innbucks.loans.core.staff.loan;

import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * A transaction manager with no database behind it that still does everything Spring's own do around a transaction:
 * {@code TransactionSynchronizationManager.isActualTransactionActive()} is true inside one and false outside, and
 * after-commit callbacks run on commit and never on rollback. So a test can assert what runs inside a transaction, and
 * what (a bank call) does not.
 */
final class CountingTransactionManager extends AbstractPlatformTransactionManager {

    private int begun;
    private int committed;
    private int rolledBack;

    int begun() {
        return begun;
    }

    int committed() {
        return committed;
    }

    int rolledBack() {
        return rolledBack;
    }

    @Override
    protected Object doGetTransaction() {
        return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        begun++;
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
        committed++;
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
        rolledBack++;
    }
}
