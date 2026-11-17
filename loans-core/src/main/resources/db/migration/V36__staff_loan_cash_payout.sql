-- A Staff Grocery Loan can be paid in cash into the borrower's own account by the bank (InnBucks' pre-approved loan
-- booking, POST /bank/api/loan/apply/pre-approved) instead of by voucher. How a loan is paid, and where, is decided at
-- acceptance and never changes after (loans.staff-loans.payout.method; VOUCHER, as before, by default).
--
-- A cash payout is sent once, never twice: it is claimed (READY to SENDING) before the call, and an answer that does
-- not prove it was or was not booked holds it IN_DOUBT, where only the bank's inquiry, or a person, settles it. No
-- bank answer ever cancels a loan by itself. Every payout column is written on a row locked FOR UPDATE, or by a
-- conditional UPDATE.
--
-- Every CHECK below that compares a nullable column says IS NOT NULL first: a CHECK whose expression is NULL passes.

-- The default stays on purpose: an image from before V36 inserts without the column, and its loans are voucher loans.
ALTER TABLE staff_loans ADD COLUMN payout_method               VARCHAR(16)   NOT NULL DEFAULT 'VOUCHER';
-- Where a cash loan is paid, as at acceptance: VEENGU_WALLET (the borrower's registered phone, 2637XXXXXXXX) or
-- BR_ACCOUNT (the register's account number).
ALTER TABLE staff_loans ADD COLUMN payout_destination          VARCHAR(16);
ALTER TABLE staff_loans ADD COLUMN payout_account              VARCHAR(20);
ALTER TABLE staff_loans ADD COLUMN payout_status               VARCHAR(16);
-- When it was claimed for sending; set only while it is SENDING.
ALTER TABLE staff_loans ADD COLUMN payout_claimed_at           TIMESTAMP(6);
-- Why it stands where it does (held, refused, in doubt), for the operators.
ALTER TABLE staff_loans ADD COLUMN payout_message              VARCHAR(500);
-- The first time the bank's inquiry said it holds no loan under this reference (responseCode 004).
ALTER TABLE staff_loans ADD COLUMN payout_not_found_at         TIMESTAMP(6);
-- The first time the bank said it holds it (a 2xx apply whose envelope says 000 or carries none, or responseCode 000
-- on the inquiry). Never cleared.
ALTER TABLE staff_loans ADD COLUMN payout_bank_seen_at         TIMESTAMP(6);
-- The bank's own status of the booking, from its last inquiry answer.
ALTER TABLE staff_loans ADD COLUMN payout_bank_status          VARCHAR(64);
-- The bank's last answer to the apply or the inquiry, redacted and cut short: the evidence an operator decides on.
ALTER TABLE staff_loans ADD COLUMN payout_last_answer          VARCHAR(2000);
-- The bank's answer to the last booking sent (the apply), kept when the inquiry's answers replace the one above.
ALTER TABLE staff_loans ADD COLUMN payout_apply_answer         VARCHAR(2000);
-- When the bank last answered its inquiry about it (000 or 004): a "not booked" needs a recent answer.
ALTER TABLE staff_loans ADD COLUMN payout_checked_at           TIMESTAMP(6);
-- The first time the inquiry held it in a status that says the bank did not pay it (e.g. REJECTED); cleared by any
-- other status.
ALTER TABLE staff_loans ADD COLUMN payout_declined_at          TIMESTAMP(6);
-- When a person confirmed with the bank that it was never booked (FAILED).
ALTER TABLE staff_loans ADD COLUMN payout_failed_at            TIMESTAMP(6);
-- When the bank answered, after that, that it does hold it: the borrower may have been paid for a cancelled loan.
ALTER TABLE staff_loans ADD COLUMN payout_paid_after_cancel_at TIMESTAMP(6);
-- The bank's own loan account, from the inquiry, when it names one.
ALTER TABLE staff_loans ADD COLUMN bank_loan_account           VARCHAR(64);

ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_method CHECK (payout_method IN ('VOUCHER', 'CASH'));
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_destination
    CHECK (payout_destination IN ('VEENGU_WALLET', 'BR_ACCOUNT'));
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_status
    CHECK (payout_status IN ('READY', 'SENDING', 'BOOKED', 'IN_DOUBT', 'REFUSED', 'PAID', 'FAILED', 'WITHDRAWN'));
-- Digits only. A wallet is a Zimbabwean mobile number; an account number is never one (the register keeps a wallet
-- in the same field, so a wallet-shaped value there is never taken for an account).
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_account
    CHECK (payout_account IS NULL
        OR (payout_account ~ '^[0-9]{6,20}$'
            AND (payout_destination IS NOT NULL AND payout_destination = 'VEENGU_WALLET')
                = (payout_account ~ '^2637[13789][0-9]{7}$')));
-- A cash loan has a destination, an account and a payout status; a voucher loan has none of the payout columns.
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout
    CHECK ((payout_method = 'VOUCHER'
            AND payout_destination IS NULL AND payout_account IS NULL AND payout_status IS NULL
            AND payout_claimed_at IS NULL AND payout_message IS NULL AND payout_not_found_at IS NULL
            AND payout_bank_seen_at IS NULL AND payout_bank_status IS NULL AND payout_last_answer IS NULL
            AND payout_apply_answer IS NULL AND payout_checked_at IS NULL AND payout_declined_at IS NULL
            AND payout_failed_at IS NULL AND payout_paid_after_cancel_at IS NULL AND bank_loan_account IS NULL)
        OR (payout_method = 'CASH'
            AND payout_destination IS NOT NULL AND payout_account IS NOT NULL AND payout_status IS NOT NULL));
-- A cash loan is cancelled only WITHDRAWN (before anything was sent, or after the bank refused it) or FAILED (a
-- person confirmed it was never booked), and both always cancel it. This also stops an image from before V36
-- cancelling a cash loan the bank may have booked.
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_cancelled
    CHECK ((status <> 'CANCELLED' OR payout_method <> 'CASH'
            OR (payout_status IS NOT NULL AND payout_status IN ('WITHDRAWN', 'FAILED')))
        AND (payout_status IS NULL OR payout_status NOT IN ('WITHDRAWN', 'FAILED') OR status = 'CANCELLED'));
-- Paid exactly when the cash loan counts as paid out.
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_paid
    CHECK ((payout_status IS NOT NULL AND payout_status = 'PAID')
        = (payout_method = 'CASH' AND status IN ('DISBURSED', 'REPAID', 'WRITTEN_OFF')));
-- Claimed exactly while it is being sent.
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_claimed
    CHECK ((payout_status IS NOT NULL AND payout_status = 'SENDING') = (payout_claimed_at IS NOT NULL));
-- Failed exactly when stamped so; paid-after-cancel only on a failed one.
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_payout_failed
    CHECK ((payout_status IS NOT NULL AND payout_status = 'FAILED') = (payout_failed_at IS NOT NULL)
        AND (payout_paid_after_cancel_at IS NULL OR (payout_status IS NOT NULL AND payout_status = 'FAILED')));

-- The payout queues: READY (to send), SENDING (claims to hold when stale), BOOKED and IN_DOUBT (to ask the bank about),
-- REFUSED (for a person). The queries repeat these literals, so the planner can prove them against the predicate.
CREATE INDEX idx_staff_loans_payout_pending ON staff_loans (payout_status, id)
    WHERE payout_method = 'CASH' AND status = 'AWAITING_DISBURSEMENT';
-- Payouts a person confirmed were never booked, still asked about for a while in case the bank booked them after all.
CREATE INDEX idx_staff_loans_payout_failed ON staff_loans (payout_failed_at, id)
    WHERE payout_status = 'FAILED' AND payout_paid_after_cancel_at IS NULL;

-- "Paid" and "not paid" reach the borrower as staff notifications about their loan: in-app, then WhatsApp, then SMS.
ALTER TABLE staff_notifications ADD COLUMN staff_loan_id BIGINT;
ALTER TABLE staff_notifications ADD CONSTRAINT fk_staff_notifications_loan
    FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id);
ALTER TABLE staff_notifications DROP CONSTRAINT ck_staff_notifications_template;
ALTER TABLE staff_notifications ADD CONSTRAINT ck_staff_notifications_template
    CHECK (template IN ('OFFER_NEW', 'OFFER_REFRESHED', 'LAUNCH', 'LOAN_PAID_OUT', 'LOAN_NOT_PAID_OUT'));
-- An offer notification names its offer and run; a broadcast's names its broadcast; a loan notice names its loan.
ALTER TABLE staff_notifications DROP CONSTRAINT ck_staff_notifications_subject;
ALTER TABLE staff_notifications ADD CONSTRAINT ck_staff_notifications_subject
    CHECK ((template IN ('OFFER_NEW', 'OFFER_REFRESHED')) = (offer_id IS NOT NULL)
        AND (offer_id IS NULL) = (run_id IS NULL)
        AND (template = 'LAUNCH') = (broadcast_id IS NOT NULL)
        AND (template IN ('LOAN_PAID_OUT', 'LOAN_NOT_PAID_OUT')) = (staff_loan_id IS NOT NULL));
-- One notice of each kind per loan, however often its payout is settled again.
CREATE UNIQUE INDEX uq_staff_notifications_loan ON staff_notifications (staff_loan_id, template)
    WHERE staff_loan_id IS NOT NULL;
