-- Staff Grocery Loan offers (FR-SGL-015 to FR-SGL-018, FR-SGL-024). A weekly run gives every eligible member of the
-- staff register a pre-approved offer at their grade's limit, valid for a configurable number of days.
--
-- A run belongs to a cycle: the market week it falls in, named by its Monday. Each member gets at most one offer per
-- cycle (uq_staff_offers_cycle), so running again in the same week only tops up the members still without one: a
-- failed, refused or repeated run never issues a second offer. A whole run is one transaction, so it never commits
-- half its offers.

-- Every attempt at a run, kept whatever its outcome.
CREATE TABLE staff_offer_runs (
    id                         BIGSERIAL    NOT NULL,
    cycle_start                DATE         NOT NULL,
    run_trigger                VARCHAR(16)  NOT NULL,
    started_by                 VARCHAR(255) NOT NULL,
    started_at                 TIMESTAMP(6) NOT NULL,
    finished_at                TIMESTAMP(6) NOT NULL,
    status                     VARCHAR(16)  NOT NULL,
    -- Why a run was REFUSED (the register is not reconciled recently enough) or FAILED.
    reason                     VARCHAR(500),
    -- The payroll reconciliation the run relied on.
    reconciliation_id          BIGINT,
    register_members           INTEGER,
    ineligible                 INTEGER,
    excluded_active_loan       INTEGER,
    excluded_arrears           INTEGER,
    excluded_by_reconciliation INTEGER,
    eligible                   INTEGER,
    offered                    INTEGER,
    refreshed                  INTEGER,
    already_offered            INTEGER,
    withdrawn                  INTEGER,
    expired                    INTEGER,
    CONSTRAINT staff_offer_runs_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_offer_runs_reconciliation
        FOREIGN KEY (reconciliation_id) REFERENCES staff_register_reconciliations (id),
    CONSTRAINT ck_staff_offer_runs_trigger CHECK (run_trigger IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT ck_staff_offer_runs_status CHECK (status IN ('COMPLETED', 'REFUSED', 'FAILED')),
    CONSTRAINT ck_staff_offer_runs_cycle CHECK (EXTRACT(ISODOW FROM cycle_start) = 1),
    CONSTRAINT ck_staff_offer_runs_reason CHECK ((status = 'COMPLETED') = (reason IS NULL)),
    -- Only a completed run has counts, and it always relied on a reconciliation; a refused one may name the
    -- reconciliation that was too old.
    CONSTRAINT ck_staff_offer_runs_completed
        CHECK (CASE WHEN status = 'COMPLETED'
                    THEN reconciliation_id IS NOT NULL
                        AND num_nulls(register_members, ineligible, excluded_active_loan, excluded_arrears,
                                      excluded_by_reconciliation, eligible, offered, refreshed, already_offered,
                                      withdrawn, expired) = 0
                    ELSE num_nonnulls(register_members, ineligible, excluded_active_loan, excluded_arrears,
                                      excluded_by_reconciliation, eligible, offered, refreshed, already_offered,
                                      withdrawn, expired) = 0 END),
    -- Every member is counted once: ineligible, excluded for one reason, or eligible; and every eligible member was
    -- offered, refreshed or already held this cycle's offer.
    CONSTRAINT ck_staff_offer_runs_counts
        CHECK (status <> 'COMPLETED' OR (
            register_members = ineligible + excluded_active_loan + excluded_arrears + excluded_by_reconciliation
                + eligible
            AND eligible = offered + refreshed + already_offered
            AND LEAST(ineligible, excluded_active_loan, excluded_arrears, excluded_by_reconciliation, offered,
                      refreshed, already_offered, withdrawn, expired) >= 0))
);
CREATE INDEX idx_staff_offer_runs_cycle ON staff_offer_runs (cycle_start);

CREATE TABLE staff_offers (
    id                    BIGSERIAL     NOT NULL,
    staff_member_id       BIGINT        NOT NULL,
    run_id                BIGINT        NOT NULL,
    cycle_start           DATE          NOT NULL,
    -- What the offer was based on, as it stood when issued: a later grade or matrix change does not alter it.
    grade                 VARCHAR(16)   NOT NULL,
    score_band            VARCHAR(40)   NOT NULL,
    grade_limit_change_id BIGINT        NOT NULL,
    amount                NUMERIC(19,2) NOT NULL,
    issued_at             TIMESTAMP(6)  NOT NULL,
    expires_at            TIMESTAMP(6)  NOT NULL,
    -- The earlier offer this one replaced while it was still valid (a refresh), if any.
    replaces_offer_id     BIGINT,
    status                VARCHAR(16)   NOT NULL,
    closed_at             TIMESTAMP(6),
    closed_reason         VARCHAR(255),
    version               BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT staff_offers_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_offers_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT fk_staff_offers_run FOREIGN KEY (run_id) REFERENCES staff_offer_runs (id),
    CONSTRAINT fk_staff_offers_grade_limit FOREIGN KEY (grade_limit_change_id)
        REFERENCES staff_grade_limit_changes (id),
    CONSTRAINT fk_staff_offers_replaces FOREIGN KEY (replaces_offer_id) REFERENCES staff_offers (id),
    CONSTRAINT ck_staff_offers_status CHECK (status IN ('ACTIVE', 'EXPIRED', 'SUPERSEDED', 'WITHDRAWN')),
    CONSTRAINT ck_staff_offers_amount CHECK (amount > 0),
    CONSTRAINT ck_staff_offers_expiry CHECK (expires_at > issued_at),
    CONSTRAINT ck_staff_offers_closed CHECK ((status = 'ACTIVE') = (closed_at IS NULL)),
    CONSTRAINT ck_staff_offers_withdrawn CHECK (status <> 'WITHDRAWN' OR closed_reason IS NOT NULL)
);
-- One offer per member per weekly cycle, whatever happens to it: what makes a repeated run safe (FR-SGL-024).
CREATE UNIQUE INDEX uq_staff_offers_cycle ON staff_offers (cycle_start, staff_member_id);
-- At most one live offer per member.
CREATE UNIQUE INDEX uq_staff_offers_active ON staff_offers (staff_member_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_staff_offers_run ON staff_offers (run_id);
