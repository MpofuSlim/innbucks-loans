-- The Staff Grocery Loan journey in the SuperApp (FR-SGL-012 to FR-SGL-014, FR-SGL-025 to FR-SGL-031): a borrower
-- takes up an offer, or applies for one on demand, chooses an amount, reads the disclosure and the agreement, and
-- accepts with their PIN or biometrics. The loan then waits for disbursement through the bank's system (BR.NET).

-- An offer can now be taken up, and can be made on demand when the borrower applies ("Apply", FR-SGL-025) as well as
-- by the weekly run. An on-demand offer belongs to no run.
ALTER TABLE staff_offers DROP CONSTRAINT ck_staff_offers_status;
ALTER TABLE staff_offers ADD CONSTRAINT ck_staff_offers_status
    CHECK (status IN ('ACTIVE', 'EXPIRED', 'SUPERSEDED', 'WITHDRAWN', 'TAKEN_UP'));
ALTER TABLE staff_offers ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT 'RUN';
ALTER TABLE staff_offers ALTER COLUMN origin DROP DEFAULT;
ALTER TABLE staff_offers ALTER COLUMN run_id DROP NOT NULL;
ALTER TABLE staff_offers ADD CONSTRAINT ck_staff_offers_origin
    CHECK (origin IN ('RUN', 'APPLY') AND (origin = 'RUN') = (run_id IS NOT NULL));
-- One RUN offer per member per cycle still makes a repeated run safe (FR-SGL-024). An on-demand offer does not count
-- against it: a member whose offer was taken up and cancelled, or withdrawn and since cleared, may apply again in the
-- same week. At most one live offer per member still holds (uq_staff_offers_active).
DROP INDEX uq_staff_offers_cycle;
CREATE UNIQUE INDEX uq_staff_offers_cycle ON staff_offers (cycle_start, staff_member_id) WHERE origin = 'RUN';

-- The staff loan agreement is published like the other instruments, as versioned wording.
ALTER TABLE instrument_templates DROP CONSTRAINT ck_instrument_templates_instrument_type;
ALTER TABLE instrument_templates ADD CONSTRAINT ck_instrument_templates_instrument_type
    CHECK (instrument_type IN ('LOAN_AGREEMENT', 'SSB_DEDUCTION_AUTHORITY', 'STAFF_GROCERY_LOAN_AGREEMENT'));

CREATE SEQUENCE staff_loan_reference_seq START WITH 1;

CREATE TABLE staff_loans (
    id                           BIGSERIAL     NOT NULL,
    -- SGL-2026-000143: the loan account a voucher and the disbursement name.
    reference                    VARCHAR(32)   NOT NULL,
    staff_member_id              BIGINT        NOT NULL,
    offer_id                     BIGINT        NOT NULL,
    -- Who borrowed, as the register held them when they accepted.
    employee_number              VARCHAR(32)   NOT NULL,
    full_name                    VARCHAR(160)  NOT NULL,
    msisdn                       VARCHAR(12)   NOT NULL,
    grade                        VARCHAR(16)   NOT NULL,
    -- The terms accepted, as disclosed (FR-SGL-026): none of them changes after acceptance.
    amount                       NUMERIC(19,2) NOT NULL,
    currency                     VARCHAR(3)    NOT NULL,
    interest_rate                NUMERIC(9,4)  NOT NULL,
    total_repayable              NUMERIC(19,2) NOT NULL,
    due_date                     DATE          NOT NULL,
    unredeemed_voucher_treatment VARCHAR(32)   NOT NULL,
    status                       VARCHAR(32)   NOT NULL,
    accepted_at                  TIMESTAMP(6)  NOT NULL,
    disbursed_at                 TIMESTAMP(6),
    disbursement_reference       VARCHAR(64),
    settled_at                   TIMESTAMP(6),
    cancelled_at                 TIMESTAMP(6),
    cancelled_by                 VARCHAR(255),
    cancellation_reason          VARCHAR(500),
    version                      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT staff_loans_pkey PRIMARY KEY (id),
    CONSTRAINT uq_staff_loans_reference UNIQUE (reference),
    -- An offer is taken up once.
    CONSTRAINT uq_staff_loans_offer UNIQUE (offer_id),
    CONSTRAINT fk_staff_loans_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT fk_staff_loans_offer FOREIGN KEY (offer_id) REFERENCES staff_offers (id),
    CONSTRAINT ck_staff_loans_status
        CHECK (status IN ('AWAITING_DISBURSEMENT', 'DISBURSED', 'REPAID', 'CANCELLED', 'WRITTEN_OFF')),
    CONSTRAINT ck_staff_loans_treatment
        CHECK (unredeemed_voucher_treatment IN ('DEBT_STANDS', 'REDUCED_TO_AMOUNT_SPENT')),
    CONSTRAINT ck_staff_loans_amount CHECK (amount > 0 AND total_repayable >= amount AND interest_rate >= 0),
    CONSTRAINT ck_staff_loans_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_staff_loans_msisdn CHECK (msisdn ~ '^2637[13789][0-9]{7}$'),
    -- Cancelled only before disbursement, always with who and why.
    CONSTRAINT ck_staff_loans_cancelled
        CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)
            AND (cancelled_at IS NULL) = (cancelled_by IS NULL)
            AND (cancelled_at IS NULL) = (cancellation_reason IS NULL)
            AND (status <> 'CANCELLED' OR disbursed_at IS NULL)),
    -- Money moved only for a disbursed, repaid or written-off loan, and always under a reference.
    CONSTRAINT ck_staff_loans_disbursed
        CHECK ((status IN ('DISBURSED', 'REPAID', 'WRITTEN_OFF')) = (disbursed_at IS NOT NULL)
            AND (disbursed_at IS NULL) = (disbursement_reference IS NULL)),
    CONSTRAINT ck_staff_loans_settled CHECK ((status IN ('REPAID', 'WRITTEN_OFF')) = (settled_at IS NOT NULL))
);
-- FR-SGL-013: one loan under this product at a time. The service refuses a second; this refuses a race.
CREATE UNIQUE INDEX uq_staff_loans_open ON staff_loans (staff_member_id)
    WHERE status IN ('AWAITING_DISBURSEMENT', 'DISBURSED');
CREATE INDEX idx_staff_loans_status ON staff_loans (status);

-- What the borrower accepted (FR-SGL-027, FR-SGL-028): the exact agreement text they were shown, and the evidence of
-- the acceptance, sealed by a hash over all of it. Never changed or removed.
CREATE TABLE staff_loan_agreements (
    id                    BIGSERIAL    NOT NULL,
    staff_loan_id         BIGINT       NOT NULL,
    instrument_type       VARCHAR(32)  NOT NULL,
    template_version      INTEGER      NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    content               TEXT         NOT NULL,
    content_sha256        VARCHAR(64)  NOT NULL,
    accepted_by           VARCHAR(255) NOT NULL,
    accepted_at           TIMESTAMP(6) NOT NULL,
    device_id             VARCHAR(128) NOT NULL,
    ip_address            VARCHAR(64)  NOT NULL,
    forwarded_for         VARCHAR(512),
    user_agent            VARCHAR(512),
    -- How the acceptance was authenticated: the middleware's amr, "pin", "fpt" or "face".
    authentication_method VARCHAR(64)  NOT NULL,
    -- The middleware assertion that authenticated it (its jti), spent by the acceptance.
    assertion_id          VARCHAR(128) NOT NULL,
    evidence_sha256       VARCHAR(64)  NOT NULL,
    CONSTRAINT staff_loan_agreements_pkey PRIMARY KEY (id),
    CONSTRAINT uq_staff_loan_agreements_loan UNIQUE (staff_loan_id),
    CONSTRAINT fk_staff_loan_agreements_loan FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id),
    CONSTRAINT fk_staff_loan_agreements_template
        FOREIGN KEY (instrument_type, template_version) REFERENCES instrument_templates (instrument_type, version),
    CONSTRAINT ck_staff_loan_agreements_type CHECK (instrument_type = 'STAFF_GROCERY_LOAN_AGREEMENT')
);
CREATE TRIGGER trg_staff_loan_agreements_append_only
    BEFORE UPDATE OR DELETE ON staff_loan_agreements
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_staff_loan_agreements_no_truncate
    BEFORE TRUNCATE ON staff_loan_agreements
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();
