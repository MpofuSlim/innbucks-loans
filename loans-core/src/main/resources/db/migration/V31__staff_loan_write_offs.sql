-- Writing off a Staff Grocery Loan, and reversing a write-off (FR-GEN-011): "write-off, reversal and adjustment with
-- restricted entitlements, maker-checker and full audit trail".
--
-- A CREDIT_MANAGER, FINANCE or SUPER_ADMIN user proposes one for a loan, with a reason; FINANCE or a SUPER_ADMIN
-- approves or rejects it, never the proposer. A written-off loan is still owed and still recovered; it also stops the
-- borrower taking another Staff Grocery Loan unless Credit overrides (FR-SGL-014). A reversal puts a loan written off
-- in error back to DISBURSED.
CREATE TABLE staff_loan_write_offs (
    id               BIGSERIAL      NOT NULL,
    staff_loan_id    BIGINT         NOT NULL,
    kind             VARCHAR(24)    NOT NULL,
    -- What the loan owed when it was proposed, as loans knows it: the amount written off, or put back.
    amount           NUMERIC(19, 2) NOT NULL,
    currency         VARCHAR(3)     NOT NULL,
    reason           VARCHAR(255)   NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    proposed_by      VARCHAR(255)   NOT NULL,
    proposed_at      TIMESTAMP(6)   NOT NULL,
    decided_by       VARCHAR(255),
    decided_at       TIMESTAMP(6),
    decision_comment VARCHAR(255),
    version          BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT staff_loan_write_offs_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_loan_write_offs_loan FOREIGN KEY (staff_loan_id) REFERENCES staff_loans (id),
    CONSTRAINT ck_staff_loan_write_offs_kind CHECK (kind IN ('WRITE_OFF', 'WRITE_OFF_REVERSAL')),
    CONSTRAINT ck_staff_loan_write_offs_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_staff_loan_write_offs_amount CHECK (amount > 0),
    CONSTRAINT ck_staff_loan_write_offs_decided
        CHECK ((status = 'PENDING') = (decided_by IS NULL) AND (decided_by IS NULL) = (decided_at IS NULL)),
    -- Maker-checker: whoever proposed it never approves or rejects it. Only they may withdraw it.
    CONSTRAINT ck_staff_loan_write_offs_checker
        CHECK (status NOT IN ('APPROVED', 'REJECTED') OR lower(decided_by) <> lower(proposed_by)),
    CONSTRAINT ck_staff_loan_write_offs_withdrawn CHECK (status <> 'WITHDRAWN' OR decided_by = proposed_by)
);
-- One request waiting per loan.
CREATE UNIQUE INDEX uq_staff_loan_write_offs_pending ON staff_loan_write_offs (staff_loan_id) WHERE status = 'PENDING';
CREATE INDEX ix_staff_loan_write_offs_loan ON staff_loan_write_offs (staff_loan_id, id);
