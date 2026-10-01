-- Grocery vouchers (FR-SGL-033 to FR-SGL-040). A loan under the Staff Grocery Loan is paid out as a voucher, never
-- as cash: one voucher per disbursement, worth the amount disbursed, sent to the customer by SMS (WhatsApp when it
-- fails) and redeemed at GetMore's tills, in part or in full, until it expires.
--
-- The code is all digits, 16 by default: random, the first never 0, the last a Damm check digit. It is never stored as
-- written: code_hmac (HMAC-SHA256) finds a voucher by its code, code_ciphertext (AES-256-GCM) gives it back to the
-- customer and to staff entitled to see it, and code_last4 and code_length are all anyone else is shown.

-- GetMore's till integration signs in as a user of its own, and the staff who may read a voucher code in full.
ALTER TABLE user_groups DROP CONSTRAINT ck_user_groups_user_group;
ALTER TABLE user_groups ADD CONSTRAINT ck_user_groups_user_group
    CHECK (user_group IN ('AGENTS', 'SUPER_ADMIN', 'CREDIT_MANAGER', 'FINANCE', 'HUMAN_CAPITAL', 'GETMORE',
                          'VOUCHER_SUPPORT'));

CREATE TABLE vouchers (
    id                     BIGSERIAL      NOT NULL,
    product                VARCHAR(32)    NOT NULL,
    -- The payout this voucher is: one voucher per disbursement, however often issuing it is retried.
    disbursement_reference VARCHAR(64)    NOT NULL,
    loan_account           VARCHAR(64)    NOT NULL,
    staff_member_id        BIGINT,
    customer_reference     VARCHAR(64)    NOT NULL,
    customer_name          VARCHAR(160)   NOT NULL,
    customer_msisdn        VARCHAR(16)    NOT NULL,
    code_hmac              VARCHAR(64)    NOT NULL,
    code_ciphertext        VARCHAR(255)   NOT NULL,
    code_last4             VARCHAR(4)     NOT NULL,
    code_length            INTEGER        NOT NULL,
    face_value             NUMERIC(19, 2) NOT NULL,
    redeemed_amount        NUMERIC(19, 2) NOT NULL DEFAULT 0,
    currency               VARCHAR(3)     NOT NULL,
    issued_at              TIMESTAMP(6)   NOT NULL,
    expires_at             TIMESTAMP(6)   NOT NULL,
    status                 VARCHAR(20)    NOT NULL,
    last_redeemed_at       TIMESTAMP(6),
    last_redeemed_outlet   VARCHAR(120),
    cancelled_by           VARCHAR(255),
    cancelled_at           TIMESTAMP(6),
    cancellation_reason    VARCHAR(255),
    -- The SMS or WhatsApp carrying the code: PENDING until claimed, SENDING while sent, then SENT or FAILED.
    delivery_status        VARCHAR(16)    NOT NULL,
    delivered_channel      VARCHAR(16),
    delivery_updated_at    TIMESTAMP(6)   NOT NULL,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT vouchers_pkey PRIMARY KEY (id),
    CONSTRAINT uq_vouchers_disbursement UNIQUE (disbursement_reference),
    CONSTRAINT uq_vouchers_code UNIQUE (code_hmac),
    CONSTRAINT fk_vouchers_staff_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT ck_vouchers_product CHECK (product IN ('STAFF_GROCERY_LOAN')),
    CONSTRAINT ck_vouchers_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_vouchers_amounts
        CHECK (face_value > 0 AND redeemed_amount >= 0 AND redeemed_amount <= face_value),
    CONSTRAINT ck_vouchers_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_vouchers_code_shape
        CHECK (code_last4 ~ '^[0-9]{4}$' AND code_length BETWEEN 12 AND 24 AND code_length % 4 = 0),
    CONSTRAINT ck_vouchers_status
        CHECK (status IN ('ISSUED', 'PARTIALLY_REDEEMED', 'REDEEMED', 'EXPIRED', 'CANCELLED')),
    -- What has been redeemed agrees with the status; a cancelled voucher was never redeemed.
    CONSTRAINT ck_vouchers_redeemed
        CHECK ((status = 'ISSUED' AND redeemed_amount = 0)
            OR (status = 'PARTIALLY_REDEEMED' AND redeemed_amount > 0 AND redeemed_amount < face_value)
            OR (status = 'REDEEMED' AND redeemed_amount = face_value)
            OR (status = 'EXPIRED' AND redeemed_amount < face_value)
            OR (status = 'CANCELLED' AND redeemed_amount = 0)),
    CONSTRAINT ck_vouchers_last_redemption
        CHECK ((redeemed_amount > 0) = (last_redeemed_at IS NOT NULL)
            AND (last_redeemed_at IS NULL) = (last_redeemed_outlet IS NULL)),
    CONSTRAINT ck_vouchers_cancelled
        CHECK ((status = 'CANCELLED') = (cancelled_by IS NOT NULL)
            AND (cancelled_by IS NULL) = (cancelled_at IS NULL)
            AND (cancelled_by IS NULL) = (cancellation_reason IS NULL)),
    CONSTRAINT ck_vouchers_delivery_status CHECK (delivery_status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    CONSTRAINT ck_vouchers_delivered_channel
        CHECK ((delivery_status = 'SENT') = (delivered_channel IS NOT NULL)
            AND (delivered_channel IS NULL OR delivered_channel IN ('SMS', 'WHATSAPP')))
);
CREATE INDEX ix_vouchers_staff_member ON vouchers (staff_member_id, id);
CREATE INDEX ix_vouchers_issued_at ON vouchers (issued_at);
CREATE INDEX ix_vouchers_open ON vouchers (expires_at) WHERE status IN ('ISSUED', 'PARTIALLY_REDEEMED');
CREATE INDEX ix_vouchers_delivery ON vouchers (id) WHERE delivery_status IN ('PENDING', 'FAILED');

-- Each redemption at a GetMore till. GetMore's own transaction reference identifies it, so a till that retries the
-- same redemption gets the first answer back instead of redeeming twice.
CREATE TABLE voucher_redemptions (
    id                 BIGSERIAL      NOT NULL,
    voucher_id         BIGINT         NOT NULL,
    merchant_reference VARCHAR(64)    NOT NULL,
    amount             NUMERIC(19, 2) NOT NULL,
    balance_after      NUMERIC(19, 2) NOT NULL,
    outlet_id          VARCHAR(64)    NOT NULL,
    outlet_name        VARCHAR(120),
    redeemed_by        VARCHAR(255)   NOT NULL,
    redeemed_at        TIMESTAMP(6)   NOT NULL,
    CONSTRAINT voucher_redemptions_pkey PRIMARY KEY (id),
    CONSTRAINT fk_voucher_redemptions_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id),
    CONSTRAINT uq_voucher_redemptions_reference UNIQUE (merchant_reference),
    CONSTRAINT ck_voucher_redemptions_amounts CHECK (amount > 0 AND balance_after >= 0)
);
CREATE INDEX ix_voucher_redemptions_voucher ON voucher_redemptions (voucher_id, id);
CREATE INDEX ix_voucher_redemptions_redeemed_at ON voucher_redemptions (redeemed_at);

-- Every attempt to send a customer their voucher, on each channel. Never changed once written; never holds the code.
CREATE TABLE voucher_deliveries (
    id                BIGSERIAL    NOT NULL,
    voucher_id        BIGINT       NOT NULL,
    channel           VARCHAR(16)  NOT NULL,
    recipient         VARCHAR(16)  NOT NULL,
    template          VARCHAR(32)  NOT NULL,
    template_version  INTEGER      NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    gateway_reference VARCHAR(64),
    failure_reason    VARCHAR(255),
    requested_by      VARCHAR(255) NOT NULL,
    attempted_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT voucher_deliveries_pkey PRIMARY KEY (id),
    CONSTRAINT fk_voucher_deliveries_voucher FOREIGN KEY (voucher_id) REFERENCES vouchers (id),
    CONSTRAINT ck_voucher_deliveries_channel CHECK (channel IN ('SMS', 'WHATSAPP')),
    CONSTRAINT ck_voucher_deliveries_status
        CHECK (status IN ('SENT', 'FAILED') AND (status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_voucher_deliveries_reference CHECK ((channel = 'SMS') = (gateway_reference IS NOT NULL))
);
CREATE INDEX ix_voucher_deliveries_voucher ON voucher_deliveries (voucher_id, id);
