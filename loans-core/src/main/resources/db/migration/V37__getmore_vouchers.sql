-- A Staff Grocery Loan's voucher can be a GetMore Groceries coupon (loans.getmore.mode LIVE or SIMULATED): issued by
-- GetMore's store when the loan is accepted, spent in parts there, its sales and refunds read back from GetMore's
-- orders and credit memos. The loan is DISBURSED once its voucher is issued: the credit is the voucher, and the bank
-- pays GetMore on payday for what was spent. Nothing here moves money. Loans' own digit vouchers (LOANS) are unchanged.
-- Every new NOT NULL column keeps its DEFAULT, so the build before this one still writes a till redemption.

-- Which merchant's vouchers are GetMore coupons: a record, changed only by a reviewed migration (never a setting).
ALTER TABLE merchants ADD COLUMN voucher_store VARCHAR(16) NOT NULL DEFAULT 'LOANS';
ALTER TABLE merchants ADD CONSTRAINT ck_merchants_voucher_store CHECK (voucher_store IN ('LOANS', 'GETMORE'));
UPDATE merchants SET voucher_store = 'GETMORE' WHERE merchant_code = 'getmore-groceries';

ALTER TABLE vouchers ADD COLUMN code_source          VARCHAR(16)    NOT NULL DEFAULT 'LOANS';
ALTER TABLE vouchers ADD COLUMN store_mode           VARCHAR(16);
ALTER TABLE vouchers ADD COLUMN staff_loan_id        BIGINT;
ALTER TABLE vouchers ADD COLUMN external_coupon_id   BIGINT;
ALTER TABLE vouchers ADD COLUMN external_rule_id     BIGINT;
ALTER TABLE vouchers ADD COLUMN code_prefix          VARCHAR(48);
ALTER TABLE vouchers ADD COLUMN refunded_amount      NUMERIC(19, 2) NOT NULL DEFAULT 0;
ALTER TABLE vouchers ADD COLUMN held_amount          NUMERIC(19, 2) NOT NULL DEFAULT 0;   -- ordered, not invoiced
ALTER TABLE vouchers ADD COLUMN stopped_reason       VARCHAR(24);    -- null: lapsed on its own, or never stopped
ALTER TABLE vouchers ADD COLUMN synced_at            TIMESTAMP(6);   -- start of the last GetMore snapshot applied
ALTER TABLE vouchers ADD COLUMN sync_blocked_by      VARCHAR(32);    -- the blocking issue the last sync stopped at
ALTER TABLE vouchers ADD COLUMN coupon_expiry_set_at TIMESTAMP(6);   -- expiration_date confirmed at the store
ALTER TABLE vouchers ADD COLUMN coupon_withdrawn_at  TIMESTAMP(6);   -- code confirmed gone at the store
ALTER TABLE vouchers ADD CONSTRAINT fk_vouchers_staff_loan FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id);
ALTER TABLE vouchers ADD CONSTRAINT ck_vouchers_code_source CHECK (
       (code_source = 'LOANS' AND store_mode IS NULL AND staff_loan_id IS NULL AND external_coupon_id IS NULL
            AND external_rule_id IS NULL AND code_prefix IS NULL AND refunded_amount = 0 AND held_amount = 0
            AND stopped_reason IS NULL AND synced_at IS NULL AND sync_blocked_by IS NULL
            AND coupon_expiry_set_at IS NULL AND coupon_withdrawn_at IS NULL)
    OR (code_source = 'GETMORE' AND store_mode IN ('LIVE', 'SIMULATED') AND staff_loan_id IS NOT NULL
            AND external_coupon_id IS NOT NULL AND external_rule_id IS NOT NULL AND code_prefix IS NOT NULL
            AND refunded_amount >= 0 AND held_amount >= 0
            AND (stopped_reason IS NULL OR stopped_reason IN
                 ('BANK_STOPPED', 'STORE_WITHDRAWN', 'SINGLE_USE_EXHAUSTED', 'SIMULATOR_EXPIRED'))));
ALTER TABLE vouchers DROP CONSTRAINT ck_vouchers_code_shape;
ALTER TABLE vouchers ADD CONSTRAINT ck_vouchers_code_shape CHECK (
       (code_source = 'LOANS' AND code_last4 ~ '^[0-9]{4}$' AND code_length BETWEEN 12 AND 24 AND code_length % 4 = 0)
    OR (code_source = 'GETMORE' AND code_last4 ~ '^[A-Z0-9]{4}$' AND code_length BETWEEN 8 AND 64));
-- A refund can take what is spent back to nothing; when it was last spent stays on record.
ALTER TABLE vouchers DROP CONSTRAINT ck_vouchers_last_redemption;
ALTER TABLE vouchers ADD CONSTRAINT ck_vouchers_last_redemption CHECK (
    (CASE WHEN code_source = 'LOANS' THEN (redeemed_amount > 0) = (last_redeemed_at IS NOT NULL)
          ELSE (redeemed_amount = 0 OR last_redeemed_at IS NOT NULL) END)
    AND (last_redeemed_at IS NULL) = (last_redeemed_outlet IS NULL));
-- Cancelled only if never spent: nothing spent now (ck_vouchers_redeemed), nothing ever refunded, nothing held.
ALTER TABLE vouchers ADD CONSTRAINT ck_vouchers_cancelled_unspent
    CHECK (status <> 'CANCELLED' OR (refunded_amount = 0 AND held_amount = 0));
CREATE UNIQUE INDEX uq_vouchers_staff_loan ON vouchers (staff_loan_id) WHERE staff_loan_id IS NOT NULL;
CREATE UNIQUE INDEX uq_vouchers_external_coupon ON vouchers (store_mode, external_coupon_id)
    WHERE external_coupon_id IS NOT NULL;
CREATE INDEX ix_vouchers_getmore_open ON vouchers (expires_at, id)
    WHERE code_source = 'GETMORE' AND coupon_withdrawn_at IS NULL;

-- A GetMore sale, synced: one row per order per invoiced level (merchant_reference GM:O:<order>:<cents>, GMS: when
-- simulated), so uq_voucher_redemptions_reference (merchant_id, merchant_reference) records each level once. Till
-- references starting GM: or GMS: are refused at the till (VoucherRedemptionRequest), so a till row never takes a key.
ALTER TABLE voucher_redemptions ADD COLUMN source                VARCHAR(16) NOT NULL DEFAULT 'TILL';
ALTER TABLE voucher_redemptions ADD COLUMN store_mode            VARCHAR(16);
ALTER TABLE voucher_redemptions ADD COLUMN external_order_id     BIGINT;
ALTER TABLE voucher_redemptions ADD COLUMN external_order_number VARCHAR(50);
ALTER TABLE voucher_redemptions ADD COLUMN recorded_at           TIMESTAMP(6);
ALTER TABLE voucher_redemptions ADD CONSTRAINT ck_voucher_redemptions_source CHECK (
       (source = 'TILL' AND store_mode IS NULL AND external_order_id IS NULL AND external_order_number IS NULL)
    OR (source = 'GETMORE_SYNC' AND store_mode IN ('LIVE', 'SIMULATED') AND external_order_id IS NOT NULL
            AND recorded_at IS NOT NULL));
CREATE INDEX ix_voucher_redemptions_external_order ON voucher_redemptions (voucher_id, external_order_id)
    WHERE external_order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_voucher_redemptions_getmore ON voucher_redemptions (store_mode, external_order_id,
    merchant_reference) WHERE source = 'GETMORE_SYNC';
CREATE INDEX ix_voucher_redemptions_recorded_at ON voucher_redemptions (recorded_at)
    WHERE source = 'GETMORE_SYNC';

-- A refund of a GetMore sale, from a credit memo in state 2 (refunded): each memo once. Amounts positive.
CREATE TABLE voucher_refunds (
    id                         BIGSERIAL      NOT NULL,
    voucher_id                 BIGINT         NOT NULL,
    merchant_id                BIGINT         NOT NULL,
    merchant_reference         VARCHAR(64)    NOT NULL,   -- GM:CM:<creditmemo> / GMS:CM:<creditmemo>
    store_mode                 VARCHAR(16)    NOT NULL,
    external_order_id          BIGINT         NOT NULL,
    external_creditmemo_id     BIGINT         NOT NULL,
    external_creditmemo_number VARCHAR(50),
    amount                     NUMERIC(19, 2) NOT NULL,
    balance_after              NUMERIC(19, 2) NOT NULL,
    refunded_at                TIMESTAMP(6)   NOT NULL,   -- the credit memo's created_at, UTC
    recorded_at                TIMESTAMP(6)   NOT NULL,
    recorded_by                VARCHAR(255)   NOT NULL,
    CONSTRAINT voucher_refunds_pkey PRIMARY KEY (id),
    CONSTRAINT fk_voucher_refunds_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id),
    CONSTRAINT fk_voucher_refunds_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT uq_voucher_refunds_reference UNIQUE (merchant_id, merchant_reference),
    CONSTRAINT uq_voucher_refunds_creditmemo UNIQUE (store_mode, external_creditmemo_id),
    CONSTRAINT ck_voucher_refunds_mode CHECK (store_mode IN ('LIVE', 'SIMULATED')),
    CONSTRAINT ck_voucher_refunds_amounts CHECK (amount > 0 AND balance_after >= 0)
);
CREATE INDEX ix_voucher_refunds_voucher ON voucher_refunds (voucher_id, id);
CREATE INDEX ix_voucher_refunds_refunded_at ON voucher_refunds (refunded_at);
CREATE INDEX ix_voucher_refunds_recorded_at ON voucher_refunds (recorded_at);

-- What a sync could not apply (blocking: the voucher stops there) or applied with a doubt (a warning). Never holds a
-- code. Cleared when a later complete sync no longer sees it; seen again, it reopens.
CREATE TABLE voucher_sync_issues (
    id            BIGSERIAL    NOT NULL,
    voucher_id    BIGINT       NOT NULL,
    kind          VARCHAR(32)  NOT NULL,
    blocking      BOOLEAN      NOT NULL,
    external_ref  VARCHAR(64)  NOT NULL,   -- order:<id> | creditmemo:<id> | coupon:<id> | voucher
    detail        VARCHAR(500) NOT NULL,
    first_seen_at TIMESTAMP(6) NOT NULL,
    last_seen_at  TIMESTAMP(6) NOT NULL,
    cleared_at    TIMESTAMP(6),
    CONSTRAINT voucher_sync_issues_pkey PRIMARY KEY (id),
    CONSTRAINT fk_voucher_sync_issues_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id),
    CONSTRAINT uq_voucher_sync_issues UNIQUE (voucher_id, kind, external_ref),
    CONSTRAINT ck_voucher_sync_issues_kind CHECK (kind IN ('OVERSPENT', 'OTHER_RULES_APPLIED', 'CURRENCY_MISMATCH',
        'PRECISION', 'SIGN', 'AMOUNT_EXCEEDS_FACE', 'INVOICED_DECREASED', 'REFUND_EXCEEDS_SPEND',
        'SPEND_ON_CANCELLED', 'TOO_MANY_ORDERS', 'REFUND_WITHOUT_SALE', 'MEMO_REVERSED', 'LATE_SPEND',
        'ORDER_VANISHED', 'EXTRA_COUPON', 'SPEND_ON_OTHER_COUPON', 'COUPON_GONE', 'COUPON_EXHAUSTED')),
    CONSTRAINT ck_voucher_sync_issues_seen CHECK (last_seen_at >= first_seen_at)
);
CREATE INDEX ix_voucher_sync_issues_open ON voucher_sync_issues (first_seen_at, id) WHERE cleared_at IS NULL;

-- Issuing a voucher at acceptance: written in the acceptance's own transaction, worked after it commits by calling
-- GetMore with no transaction open, never by a job. coupon_prefix carries a random nonce drawn at queue time.
CREATE TABLE staff_loan_voucher_issuances (
    id                      BIGSERIAL      NOT NULL,
    staff_loan_id           BIGINT         NOT NULL,
    merchant_id             BIGINT         NOT NULL,
    store_mode              VARCHAR(16)    NOT NULL,
    coupon_prefix           VARCHAR(48)    NOT NULL,
    rule_id                 BIGINT         NOT NULL,
    face_value              NUMERIC(19, 2) NOT NULL,
    currency                VARCHAR(3)     NOT NULL,
    status                  VARCHAR(16)    NOT NULL,
    attempts                INTEGER        NOT NULL DEFAULT 0,
    generate_calls          INTEGER        NOT NULL DEFAULT 0,   -- generates that may have reached GetMore
    generate_allowance      INTEGER        NOT NULL DEFAULT 1,   -- raised only by an operator's generateAgain
    claimed_at              TIMESTAMP(6),
    first_claimed_at        TIMESTAMP(6),                        -- adoption floor (minus clock-skew)
    last_claimed_at         TIMESTAMP(6),                        -- cleanup waits stale-claim past it
    minted_codes_ciphertext TEXT,                                -- vault-encrypted codes GetMore returned, not bound
    next_attempt_at         TIMESTAMP(6)   NOT NULL,
    last_outcome            VARCHAR(48),
    last_error              VARCHAR(255),
    voucher_id              BIGINT,
    created_at              TIMESTAMP(6)   NOT NULL,
    updated_at              TIMESTAMP(6)   NOT NULL,
    version                 BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT staff_loan_voucher_issuances_pkey PRIMARY KEY (id),
    CONSTRAINT uq_slvi_loan UNIQUE (staff_loan_id),
    CONSTRAINT uq_slvi_prefix UNIQUE (store_mode, coupon_prefix),
    CONSTRAINT uq_slvi_voucher UNIQUE (voucher_id),
    CONSTRAINT fk_slvi_loan FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id),
    CONSTRAINT fk_slvi_merchant FOREIGN KEY (merchant_id) REFERENCES merchants (id),
    CONSTRAINT fk_slvi_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id),
    CONSTRAINT ck_slvi_mode CHECK (store_mode IN ('LIVE', 'SIMULATED')),
    CONSTRAINT ck_slvi_status
        CHECK (status IN ('PENDING', 'CALLING', 'ISSUED', 'PARKED', 'CANCELLING', 'ABANDONED')),
    CONSTRAINT ck_slvi_amount CHECK (face_value > 0 AND currency ~ '^[A-Z]{3}$' AND attempts >= 0
        AND generate_calls >= 0 AND generate_calls <= generate_allowance),
    CONSTRAINT ck_slvi_issued CHECK ((status = 'ISSUED') = (voucher_id IS NOT NULL)),
    CONSTRAINT ck_slvi_claimed CHECK ((status = 'CALLING') = (claimed_at IS NOT NULL)
        AND (claimed_at IS NULL OR (first_claimed_at IS NOT NULL AND last_claimed_at IS NOT NULL)))
);
CREATE INDEX ix_slvi_open ON staff_loan_voucher_issuances (next_attempt_at, id)
    WHERE status IN ('PENDING', 'CALLING', 'PARKED', 'CANCELLING');

-- A Staff Grocery Loan is DISBURSED when its GetMore voucher is issued; such a loan may still be cancelled while nothing
-- of the voucher was ever spent (the service checks that). Every other disbursed loan keeps V24's guard.
ALTER TABLE staff_loans DROP CONSTRAINT ck_staff_loans_cancelled;
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_cancelled
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
        AND (cancelled_at IS NULL) = (cancelled_by IS NULL)
        AND (cancelled_at IS NULL) = (cancellation_reason IS NULL)
        AND (status <> 'CANCELLED' OR disbursed_at IS NULL OR disbursement_reference LIKE 'GETMORE-%'));
ALTER TABLE staff_loans DROP CONSTRAINT ck_staff_loans_disbursed;
ALTER TABLE staff_loans ADD CONSTRAINT ck_staff_loans_disbursed
    CHECK ((status NOT IN ('DISBURSED', 'REPAID', 'WRITTEN_OFF') OR disbursed_at IS NOT NULL)
        AND (status <> 'AWAITING_DISBURSEMENT' OR disbursed_at IS NULL)
        AND (disbursed_at IS NULL) = (disbursement_reference IS NULL));
