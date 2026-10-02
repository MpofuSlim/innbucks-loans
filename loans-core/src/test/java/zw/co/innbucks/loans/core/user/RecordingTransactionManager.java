package zw.co.innbucks.loans.core.user;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Records whether a transaction is open, so a test can assert what runs inside one and what does not. */
final class RecordingTransactionManager implements PlatformTransactionManager {

    private boolean active;
    private int begun;
    private int committed;
    private int rolledBack;

    boolean active() {
        return active;
    }

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
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        active = true;
        begun++;
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        active = false;
        committed++;
    }

    @Override
    public void rollback(TransactionStatus status) {
        active = false;
        rolledBack++;
    }
}
