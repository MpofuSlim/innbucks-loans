-- The merchant a Staff Grocery Loan is spent at is now a merchant record, no longer a fixed name. Loans already keeps
-- merchants: the businesses a loan can be paid to, each with its own users. One of them is the Staff Grocery Loan's: a
-- loan is accepted for it, will be paid to it, and its voucher can be spent only at its tills. A SUPER_ADMIN can point
-- the product at another merchant; a loan already accepted, and its voucher, keep the merchant it was accepted for.

ALTER TABLE merchants ADD COLUMN staff_loan_merchant BOOLEAN NOT NULL DEFAULT FALSE;
-- At most one at a time.
CREATE UNIQUE INDEX uq_merchants_staff_loan_merchant ON merchants (staff_loan_merchant) WHERE staff_loan_merchant;

-- Until now the merchant was the setting STAFF_LOANS_MERCHANT_NAME, GetMore Groceries, and every loan, voucher and till
-- account so far is GetMore's. Its record: paid to its own account, which is not known yet (the payout through BR.NET
-- is still to be built), and the Staff Grocery Loan's merchant.
INSERT INTO merchants (merchant_code, name, disbursement_type, staff_loan_merchant, created_date, version)
SELECT 'getmore-groceries', 'GetMore Groceries', 'MERCHANT_MOBILE_WALLET', TRUE, now() AT TIME ZONE 'UTC', 0
WHERE NOT EXISTS (SELECT 1 FROM merchants WHERE merchant_code = 'getmore-groceries');
UPDATE merchants SET staff_loan_merchant = TRUE WHERE merchant_code = 'getmore-groceries';

-- The merchant each loan was accepted for: what its agreement names, and who it is paid to.
ALTER TABLE staff_loans ADD COLUMN merchant_id BIGINT;
UPDATE staff_loans SET merchant_id = (SELECT id FROM merchants WHERE merchant_code = 'getmore-groceries');
ALTER TABLE staff_loans ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE staff_loans ADD CONSTRAINT fk_staff_loans_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id);

-- The merchant whose tills may take each voucher, and no other's.
ALTER TABLE vouchers ADD COLUMN merchant_id BIGINT;
UPDATE vouchers SET merchant_id = (SELECT id FROM merchants WHERE merchant_code = 'getmore-groceries');
ALTER TABLE vouchers ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE vouchers ADD CONSTRAINT fk_vouchers_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id);
CREATE INDEX ix_vouchers_merchant ON vouchers (merchant_id, id);

-- A redemption is the merchant's sale, and its reference is the merchant's own: unique among that merchant's
-- redemptions only, since two merchants' tills may well number their sales alike.
ALTER TABLE voucher_redemptions ADD COLUMN merchant_id BIGINT;
UPDATE voucher_redemptions r SET merchant_id = v.merchant_id FROM vouchers v WHERE v.id = r.voucher_id;
ALTER TABLE voucher_redemptions ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE voucher_redemptions ADD CONSTRAINT fk_voucher_redemptions_merchant
    FOREIGN KEY (merchant_id) REFERENCES merchants (id);
ALTER TABLE voucher_redemptions DROP CONSTRAINT uq_voucher_redemptions_reference;
ALTER TABLE voucher_redemptions ADD CONSTRAINT uq_voucher_redemptions_reference
    UNIQUE (merchant_id, merchant_reference);

-- GetMore's till role becomes any merchant's: MERCHANT_TILL, which redeems only its own merchant's vouchers. Every till
-- account so far is GetMore's, so each moves to its record. Their sessions end: a token still naming GETMORE would name
-- a role the code no longer knows, and so escape the filter that keeps a till to the voucher endpoints.
ALTER TABLE user_groups DROP CONSTRAINT ck_user_groups_user_group;
UPDATE users
   SET merchant_id   = (SELECT id FROM merchants WHERE merchant_code = 'getmore-groceries'),
       token_version = COALESCE(token_version, 0) + 1
 WHERE id IN (SELECT user_id FROM user_groups WHERE user_group = 'GETMORE');
UPDATE user_groups SET user_group = 'MERCHANT_TILL' WHERE user_group = 'GETMORE';
ALTER TABLE user_groups ADD CONSTRAINT ck_user_groups_user_group
    CHECK (user_group IN ('AGENTS', 'SUPER_ADMIN', 'CREDIT_MANAGER', 'FINANCE', 'HUMAN_CAPITAL', 'MERCHANT_TILL',
                          'VOUCHER_SUPPORT'));
