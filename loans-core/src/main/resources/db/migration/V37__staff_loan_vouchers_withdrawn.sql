-- Vouchers are withdrawn (owner decision, 2026-11-17): every Staff Grocery Loan is paid in cash into the borrower's
-- InnBucks wallet through the bank (V36's cash payout). The code no longer knows a voucher, a voucher loan, the Staff
-- Grocery Loan's merchant or a merchant till.
--
-- No voucher was ever issued (nothing in the application called voucher issuance), so no borrower holds one and no
-- voucher loan was ever paid out: a voucher loan is either AWAITING_DISBURSEMENT or CANCELLED.
--
-- Nothing is dropped. vouchers, voucher_redemptions and voucher_deliveries, merchants.staff_loan_merchant, and on
-- staff_loans merchant_id, unredeemed_voucher_treatment, payout_method and payout_destination stay, unmapped: their rows
-- are history, and a later migration can drop them once that history is worth nothing.

-- 1. A voucher loan still awaiting disbursement can never be paid now: it is cancelled, as Credit would cancel it, and
--    its borrower may apply again (and is paid in cash). Audited like a cancel from the portal.
WITH cancelled AS (
    UPDATE staff_loans
       SET status              = 'CANCELLED',
           cancelled_at        = (now() AT TIME ZONE 'UTC'),
           cancelled_by        = 'SYSTEM_USER',
           cancellation_reason = 'Vouchers were withdrawn: the Staff Grocery Loan is now paid in cash into the'
                                 || ' borrower''s InnBucks wallet. The borrower may apply again.'
     WHERE payout_method = 'VOUCHER'
       AND status = 'AWAITING_DISBURSEMENT'
    RETURNING id, reference)
INSERT INTO audit_logs (event_type, entity_type, entity_id, actor_id, channel_used, detail, created_at)
SELECT 'STAFF_LOAN_CANCELLED', 'STAFF_LOAN', id::text, 'SYSTEM_USER', 'migration V37',
       'reference:' || reference || ';reason:vouchers withdrawn', (now() AT TIME ZONE 'UTC')
  FROM cancelled;

-- 2. A loan made from now on is paid in cash into the wallet. The columns are no longer written by the application,
--    so their defaults say so, and V36's CHECKs (which a cash loan meets) keep holding for every new row. The merchant
--    and the unspent-voucher treatment are no longer recorded.
ALTER TABLE staff_loans ALTER COLUMN payout_method SET DEFAULT 'CASH';
ALTER TABLE staff_loans ALTER COLUMN payout_destination SET DEFAULT 'VEENGU_WALLET';
ALTER TABLE staff_loans ALTER COLUMN merchant_id DROP NOT NULL;
ALTER TABLE staff_loans ALTER COLUMN unredeemed_voucher_treatment DROP NOT NULL;

-- 3. The till accounts existed only to redeem vouchers. Their sessions end, and an account that was nothing but a
--    till can no longer sign in (a user with no password cannot). VOUCHER_SUPPORT read voucher codes, which no longer
--    exist: its holders keep their other groups, and their sessions end so no token names it.
UPDATE users
   SET token_version = COALESCE(token_version, 0) + 1
 WHERE id IN (SELECT user_id FROM user_groups WHERE user_group IN ('MERCHANT_TILL', 'VOUCHER_SUPPORT'));
UPDATE users
   SET password = NULL
 WHERE id IN (SELECT user_id FROM user_groups WHERE user_group = 'MERCHANT_TILL')
   AND id NOT IN (SELECT user_id FROM user_groups WHERE user_group NOT IN ('MERCHANT_TILL', 'VOUCHER_SUPPORT'));
DELETE FROM user_groups WHERE user_group IN ('MERCHANT_TILL', 'VOUCHER_SUPPORT');
ALTER TABLE user_groups DROP CONSTRAINT ck_user_groups_user_group;
ALTER TABLE user_groups ADD CONSTRAINT ck_user_groups_user_group
    CHECK (user_group IN ('AGENTS', 'SUPER_ADMIN', 'CREDIT_MANAGER', 'FINANCE', 'HUMAN_CAPITAL'));

-- 4. The payout queues no longer name the method: every loan with a payout is a cash loan. The partial index follows
--    the queries' predicate, so the planner can still prove it.
DROP INDEX idx_staff_loans_payout_pending;
CREATE INDEX idx_staff_loans_payout_pending ON staff_loans (payout_status, id)
    WHERE payout_status IS NOT NULL AND status = 'AWAITING_DISBURSEMENT';
