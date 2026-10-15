-- What was settled on each payday, per store mode: the GetMore rows a finalised cycle paid for are stamped with it, so a
-- sale or refund recorded after a cycle was finalised (a late sync, a held sale invoiced later) falls into the next
-- cycle instead of being lost; and the per-loan amounts sent to payroll are kept as they were at finalisation.
-- Nothing here moves money. Additive: the build before this one ignores the new tables and columns.
CREATE TABLE staff_loan_collection_cycles (
    payday          DATE         NOT NULL,
    store_mode      VARCHAR(16)  NOT NULL,
    cutoff          TIMESTAMP(6) NOT NULL,                  -- 00:00 market time on the payday, UTC
    finalised_at    TIMESTAMP(6) NOT NULL,
    finalised_by    VARCHAR(255) NOT NULL,
    non_final_lines INTEGER      NOT NULL,
    stale_vouchers  INTEGER      NOT NULL,
    reason          VARCHAR(255),                           -- required when non_final_lines or stale_vouchers > 0
    CONSTRAINT staff_loan_collection_cycles_pkey PRIMARY KEY (payday, store_mode),
    CONSTRAINT ck_slcc_mode CHECK (store_mode IN ('LIVE', 'SIMULATED')),
    CONSTRAINT ck_slcc_reason CHECK ((non_final_lines = 0 AND stale_vouchers = 0) OR reason IS NOT NULL)
);
CREATE TABLE staff_loan_collection_lines (
    id                BIGSERIAL      NOT NULL,
    payday            DATE           NOT NULL,
    store_mode        VARCHAR(16)    NOT NULL,
    staff_loan_id     BIGINT         NOT NULL,
    treatment         VARCHAR(32)    NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    amount_to_collect NUMERIC(19, 2) NOT NULL,
    final             BOOLEAN        NOT NULL,
    flags             VARCHAR(255)   NOT NULL,              -- comma-joined, '' when none
    CONSTRAINT staff_loan_collection_lines_pkey PRIMARY KEY (id),
    CONSTRAINT fk_slcl_cycle FOREIGN KEY (payday, store_mode)
        REFERENCES staff_loan_collection_cycles (payday, store_mode),
    CONSTRAINT fk_slcl_loan FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id),
    CONSTRAINT uq_slcl UNIQUE (payday, store_mode, staff_loan_id),
    CONSTRAINT ck_slcl_amount CHECK (amount_to_collect >= 0)
);
-- The payday a GetMore sale or refund was paid to (or back from) GetMore on; null until a cycle covering it is finalised.
ALTER TABLE voucher_redemptions ADD COLUMN settlement_cycle DATE;
ALTER TABLE voucher_refunds ADD COLUMN settlement_cycle DATE;
CREATE INDEX ix_voucher_redemptions_unsettled ON voucher_redemptions (store_mode, redeemed_at)
    WHERE source = 'GETMORE_SYNC' AND settlement_cycle IS NULL;
CREATE INDEX ix_voucher_refunds_unsettled ON voucher_refunds (store_mode, refunded_at)
    WHERE settlement_cycle IS NULL;
