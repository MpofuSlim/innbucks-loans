package zw.co.innbucks.loans.core.merchant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import zw.co.innbucks.loans.core.audit.AuditLog;
import zw.co.innbucks.loans.core.audit.AuditService;
import zw.co.innbucks.loans.core.exception.ConflictException;
import zw.co.innbucks.loans.core.exception.NotFoundException;
import zw.co.innbucks.loans.core.exception.ValidationException;
import zw.co.innbucks.loans.core.loan.DisbursementType;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Which merchant the Staff Grocery Loan is for: read by Credit, Finance and Human Capital, changed by a SUPER_ADMIN to
 * a merchant paid to its own account, one at a time, on the record.
 */
class StaffLoanMerchantServiceTest {

    private MerchantRepository merchants;
    private AuditService audit;
    private StaffLoanMerchantService service;
    private Merchant getMore;
    private Merchant pickNPay;

    @BeforeEach
    void setUp() {
        merchants = mock(MerchantRepository.class);
        audit = mock(AuditService.class);
        service = new StaffLoanMerchantService(merchants, audit);
        getMore = merchant(3L, "getmore-groceries", "GetMore Groceries", DisbursementType.MERCHANT_MOBILE_WALLET, null);
        getMore.setStaffLoanMerchant(true);
        pickNPay = merchant(9L, "pick-n-pay", "Pick n Pay", DisbursementType.MERCHANT_MOBILE_WALLET, "0771234521");
        when(merchants.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        when(merchants.lockStaffLoanMerchant()).thenAnswer(i -> Optional.of(getMore));
        when(merchants.findByStaffLoanMerchantTrue()).thenAnswer(i -> Optional.of(getMore));
    }

    private Merchant merchant(long id, String code, String name, DisbursementType type, String account) {
        Merchant merchant = Merchant.builder().merchantCode(code).companyName(name).disbursementType(type)
                .accountNumber(account).build();
        merchant.setId(id);
        when(merchants.findByMerchantCode(code)).thenReturn(Optional.of(merchant));
        return merchant;
    }

    @Test
    @DisplayName("reading it: the code, the name, and whether it can be paid yet")
    void get() {
        assertThat(service.get()).isEqualTo(new StaffLoanMerchantResponse("getmore-groceries", "GetMore Groceries",
                false));

        when(merchants.findByStaffLoanMerchantTrue()).thenReturn(Optional.empty());
        assertThatThrownBy(service::get).isInstanceOf(NotFoundException.class)
                .hasMessage("No merchant is set for the Staff Grocery Loan");
    }

    @Test
    @DisplayName("changing it clears the old one first, then sets the new, and audits from and to")
    void change() {
        StaffLoanMerchantResponse response = service.change(" pick-n-pay ", "admin");

        assertThat(response).isEqualTo(new StaffLoanMerchantResponse("pick-n-pay", "Pick n Pay", true));
        assertThat(getMore.isStaffLoanMerchant()).isFalse();
        assertThat(pickNPay.isStaffLoanMerchant()).isTrue();
        InOrder order = inOrder(merchants);
        order.verify(merchants).saveAndFlush(getMore);
        order.verify(merchants).saveAndFlush(pickNPay);
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        AuditLog row = audited.getValue().build();
        assertThat(row.getEventType()).isEqualTo("STAFF_LOAN_MERCHANT_CHANGED");
        assertThat(row.getActorId()).isEqualTo("admin");
        assertThat(row.getEntityId()).isEqualTo("9");
        assertThat(row.getStateTransitionDelta()).isEqualTo("{\"from\":\"getmore-groceries\",\"to\":\"pick-n-pay\"}");
    }

    @Test
    @DisplayName("with none set before, the first is set and audited from nothing")
    void firstMerchant() {
        when(merchants.lockStaffLoanMerchant()).thenReturn(Optional.empty());

        service.change("pick-n-pay", "admin");

        assertThat(pickNPay.isStaffLoanMerchant()).isTrue();
        ArgumentCaptor<AuditLog.AuditLogBuilder> audited = ArgumentCaptor.forClass(AuditLog.AuditLogBuilder.class);
        verify(audit).record(audited.capture());
        assertThat(audited.getValue().build().getStateTransitionDelta())
                .isEqualTo("{\"from\":null,\"to\":\"pick-n-pay\"}");
    }

    @Test
    @DisplayName("naming the one already set changes nothing and records nothing")
    void sameMerchant() {
        assertThat(service.change("getmore-groceries", "admin").merchantCode()).isEqualTo("getmore-groceries");

        assertThat(getMore.isStaffLoanMerchant()).isTrue();
        verify(merchants, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("a merchant paid to the borrower, or one that does not exist, is refused; nothing changes")
    void refusals() {
        merchant(1L, "innbucks", "Innbucks", DisbursementType.CUSTOMER_MOBILE_WALLET, null);

        assertThatThrownBy(() -> service.change("innbucks", "admin")).isInstanceOf(ValidationException.class)
                .hasMessage("Merchant innbucks is not paid to its own account: a Staff Grocery Loan is paid to the"
                        + " merchant, never to the borrower, so set its disbursement type to MERCHANT_MOBILE_WALLET"
                        + " first");
        assertThatThrownBy(() -> service.change("nobody", "admin")).isInstanceOf(NotFoundException.class)
                .hasMessage("Merchant nobody not found");
        assertThat(getMore.isStaffLoanMerchant()).isTrue();
        verify(merchants, never()).saveAndFlush(any());
        verifyNoInteractions(audit);
    }

    @Test
    @DisplayName("another change landing at the same moment is a 409, not two merchants")
    void race() {
        when(merchants.saveAndFlush(pickNPay)).thenThrow(new DataIntegrityViolationException(
                "uq_merchants_staff_loan_merchant"));

        assertThatThrownBy(() -> service.change("pick-n-pay", "admin")).isInstanceOf(ConflictException.class)
                .hasMessage("The Staff Grocery Loan's merchant was changed at the same moment; read it again");
        verifyNoInteractions(audit);
    }
}
