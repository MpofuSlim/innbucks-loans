-- Indexes for the scheduled jobs, the loan list and the reports, from the
-- optimization-checklist audit; EXPLAIN-checked on Postgres 16 against 100k
-- loans. Before this, loans had no index on any status, date or FK column
-- except idx_loans_awaiting_credit, so every job poll was a sequential scan of
-- the widest table in the schema.
--
-- The partial indexes repeat the JPQL's enum literals as strings: Hibernate
-- inlines an enum literal written in a query as its STRING value, so the
-- planner can prove the query against the index predicate. A derived query
-- (findByXAndY) binds its values as parameters, which a partial index cannot
-- be proved against, so those get plain composite indexes. Keep the two in
-- step: a status renamed in code must be renamed here in a new migration.
--
-- Plain CREATE INDEX (not CONCURRENTLY): Flyway runs this in a transaction,
-- and at today's row counts the brief write lock is milliseconds.

-- NdasendaLodgementJob (every 60s): findIdsDueForLodgement, and the checkpoint
-- queue's findBeforeLodgement. Both filter on these two columns and read in id order.
CREATE INDEX IF NOT EXISTS idx_loans_lodgement_due ON loans (id)
    WHERE loan_status = 'NEW' AND lodgement_claimed_at IS NULL;

-- LoanBookingJob (every 2 min): findIdsDueForBooking, and findBeforeBooking.
CREATE INDEX IF NOT EXISTS idx_loans_booking_due ON loans (id)
    WHERE loan_status = 'APPROVED'
      AND internal_approval_status = 'APPROVED'
      AND loan_account_status = 'PENDING'
      AND booking_claimed_at IS NULL;

-- The Ndasenda response job (every 10 min): findAwaitingNdasendaOutcome reads
-- PROCESSING, or FAILED with a lodgement reference.
CREATE INDEX IF NOT EXISTS idx_loans_awaiting_ndasenda ON loans (id)
    WHERE loan_status IN ('PROCESSING', 'FAILED');

-- LoanDisbursementStatusJob (every 3 min): findByLoanAccountStatusAndDisbursementStatus
-- and its three-column twin. Derived queries, so not partial.
CREATE INDEX IF NOT EXISTS idx_loans_account_disbursement
    ON loans (loan_account_status, disbursement_status);

-- The RETURNED queue (findByInternalApprovalStatusOrderByIdAsc, a derived
-- query) and findBeforeCreditApproval (PENDING or RETURNED). The existing
-- idx_loans_awaiting_credit covers PENDING alone.
CREATE INDEX IF NOT EXISTS idx_loans_internal_approval
    ON loans (internal_approval_status, id);

-- The saga reconciler (every 60s) reads loans created since a cutoff; the loan
-- list sorts by created_date DESC, id DESC (a backward scan of this index) and
-- filters on a created_date range; portfolioByApprovalStatus groups a
-- created_date window.
CREATE INDEX IF NOT EXISTS idx_loans_created_date ON loans (created_date, id);

-- The agent-scoped loan list (createdByUserId, newest first) and the agent's
-- sales summary. created_by_user_id is a foreign key with no index until now.
CREATE INDEX IF NOT EXISTS idx_loans_created_by_user
    ON loans (created_by_user_id, created_date);

-- disbursementsByDay, commissionsByMerchant and agentPerformance: successful
-- disbursements in a date_disbursed window. Partial because every one of them
-- filters on SUCCESS as a literal.
CREATE INDEX IF NOT EXISTS idx_loans_disbursed_success ON loans (date_disbursed)
    WHERE disbursement_status = 'SUCCESS';

-- merchant_id is a foreign key with no index: the merchant-code filter on the
-- loan list and the reports joins through it.
CREATE INDEX IF NOT EXISTS idx_loans_merchant ON loans (merchant_id);

-- LoanDisbursementRepository.findByLoanId / existsByLoanId. Foreign-key column,
-- never indexed.
CREATE INDEX IF NOT EXISTS idx_loan_disbursements_loan_id ON loan_disbursements (loan_id);

-- A member's staff loans (paged, newest first) and their latest loan in a
-- status. Only the partial uq_staff_loans_open led with staff_member_id.
CREATE INDEX IF NOT EXISTS idx_staff_loans_member ON staff_loans (staff_member_id, id);

-- StaffNotificationSender's keyset read of a member's earlier offers
-- (findByStaffMemberIdAndIdLessThanOrderByIdDesc). The existing indexes lead
-- with cycle_start or are partial on ACTIVE.
CREATE INDEX IF NOT EXISTS idx_staff_offers_member ON staff_offers (staff_member_id, id);

-- The voucher settlement report: findByCancelledAtBetweenOrderByIdAsc. Partial,
-- since most vouchers are never cancelled and BETWEEN implies NOT NULL.
CREATE INDEX IF NOT EXISTS ix_vouchers_cancelled_at ON vouchers (cancelled_at)
    WHERE cancelled_at IS NOT NULL;
