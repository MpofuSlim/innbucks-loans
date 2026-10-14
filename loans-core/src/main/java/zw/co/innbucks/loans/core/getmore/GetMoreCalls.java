package zw.co.innbucks.loans.core.getmore;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Rules every {@link GetMoreStore} call obeys, whatever the mode. */
public final class GetMoreCalls {

    private GetMoreCalls() {
    }

    /**
     * A call to GetMore can take seconds; one made inside a transaction would hold its row locks and its pooled
     * database connection for all of it, and a rollback would not undo what the store did. So it is never made with
     * a transaction open.
     *
     * @throws IllegalStateException when a transaction is active on this thread
     */
    public static void requireNoTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("GetMore is never called with a transaction open");
        }
    }
}
