package zw.co.reikan.loans.core.ledger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static zw.co.reikan.loans.core.ledger.LedgerService.Leg;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private LedgerEntryRepository repository;

    @Test
    void unbalancedPosting_isRejectedBeforeAnyWrite() {
        LedgerService service = new LedgerService(repository);
        assertThrows(IllegalArgumentException.class, () -> service.post("TX-1", 1L, "USD", "d", "t",
                List.of(new Leg(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerEntryType.DEBIT, new BigDecimal("100")),
                        new Leg(LedgerAccount.DISBURSEMENT_CLEARING, LedgerEntryType.CREDIT, new BigDecimal("99")))));
        verify(repository, never()).save(any());
    }

    @Test
    void negativeOrZeroAmounts_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> LedgerService.validateBalanced(
                List.of(new Leg(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerEntryType.DEBIT, new BigDecimal("-5")),
                        new Leg(LedgerAccount.DISBURSEMENT_CLEARING, LedgerEntryType.CREDIT, new BigDecimal("-5")))));
        assertThrows(IllegalArgumentException.class, () -> LedgerService.validateBalanced(
                List.of(new Leg(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerEntryType.DEBIT, BigDecimal.ZERO),
                        new Leg(LedgerAccount.DISBURSEMENT_CLEARING, LedgerEntryType.CREDIT, BigDecimal.ZERO))));
    }

    @Test
    void singleLeg_isRejected_doubleEntryIsMandatory() {
        assertThrows(IllegalArgumentException.class, () -> LedgerService.validateBalanced(
                List.of(new Leg(LedgerAccount.FEE_INCOME, LedgerEntryType.CREDIT, BigDecimal.TEN))));
    }

    @Test
    void balancedMultiLegPosting_isAccepted() {
        assertDoesNotThrow(() -> LedgerService.validateBalanced(List.of(
                new Leg(LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerEntryType.DEBIT, new BigDecimal("100")),
                new Leg(LedgerAccount.DISBURSEMENT_CLEARING, LedgerEntryType.CREDIT, new BigDecimal("95")),
                new Leg(LedgerAccount.FEE_INCOME, LedgerEntryType.CREDIT, new BigDecimal("5")))));
    }

    @Test
    void duplicateTransactionRef_isIdempotentNoOp() {
        LedgerService service = new LedgerService(repository);
        when(repository.existsByTransactionRef("DISB-000000001")).thenReturn(true);

        boolean posted = service.postDoubleEntry("DISB-000000001", 1L, "USD", "d", "t",
                LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerAccount.DISBURSEMENT_CLEARING, BigDecimal.TEN);

        assertFalse(posted);
        verify(repository, never()).save(any());
    }

    @Test
    void freshTransactionRef_postsBothLegs() {
        LedgerService service = new LedgerService(repository);
        when(repository.existsByTransactionRef("DISB-000000002")).thenReturn(false);

        boolean posted = service.postDoubleEntry("DISB-000000002", 2L, "USD", "d", "t",
                LedgerAccount.LOAN_PRINCIPAL_RECEIVABLE, LedgerAccount.DISBURSEMENT_CLEARING, BigDecimal.TEN);

        assertTrue(posted);
        verify(repository, times(2)).save(any(LedgerEntry.class));
    }
}
