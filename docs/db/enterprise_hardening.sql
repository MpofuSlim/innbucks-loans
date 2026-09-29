-- ============================================================================
-- InnBucks Loans — Enterprise Hardening DDL (PostgreSQL)
-- ============================================================================
-- Canonical schema for the fintech-hardening layer. The application also
-- creates these tables via JPA (ddl-auto=update) from the matching entities;
-- this file is the authoritative reference AND carries the pieces the ORM
-- cannot express (the ledger immutability trigger, the public-ref sequence,
-- partial indexes). Idempotent: safe to (re)apply with psql.
--
-- Existing tables (loan_request, loan_batch, channel, ...) are NOT modified,
-- except one additive nullable column on loan_request (public_reference).
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. APPEND-ONLY DOUBLE-ENTRY LEDGER
--    Balances are DERIVED (SUM over history). Never UPDATE a monetary row.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ledger_entries (
    id               BIGSERIAL      PRIMARY KEY,
    transaction_ref  VARCHAR(64)    NOT NULL,               -- groups the legs of one logical txn
    loan_id          BIGINT,                                -- FK-lite; loan_request.id
    account          VARCHAR(48)    NOT NULL,
    entry_type       VARCHAR(8)     NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount           NUMERIC(19,4)  NOT NULL CHECK (amount > 0),   -- direction lives in entry_type
    currency         CHAR(3)        NOT NULL,
    description      VARCHAR(255),
    created_by       VARCHAR(128),
    created_at       TIMESTAMP      NOT NULL,
    -- replaying a saga step / retried call cannot double-post:
    CONSTRAINT uq_ledger_txref_account_type UNIQUE (transaction_ref, account, entry_type)
);

CREATE INDEX IF NOT EXISTS idx_ledger_loan_id ON ledger_entries (loan_id);
CREATE INDEX IF NOT EXISTS idx_ledger_account ON ledger_entries (account);
CREATE INDEX IF NOT EXISTS idx_ledger_tx_ref  ON ledger_entries (transaction_ref);

-- Engine-level immutability: reject UPDATE/DELETE regardless of the caller
-- (ORM, psql, migration script). Corrections are new reversing entries.
CREATE OR REPLACE FUNCTION ledger_entries_immutable() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is forbidden. Post a reversing entry instead.', TG_OP;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_ledger_entries_immutable ON ledger_entries;
CREATE TRIGGER trg_ledger_entries_immutable
    BEFORE UPDATE OR DELETE ON ledger_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_entries_immutable();

-- Derived balance per account (asset convention: debit +, credit -):
--   SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE -amount END), 0)
--   FROM ledger_entries WHERE account = :account;

-- ----------------------------------------------------------------------------
-- 2. AUDIT LOGS (channel, actor, state delta, payload hash)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_logs (
    id                      BIGSERIAL    PRIMARY KEY,
    event_type              VARCHAR(64)  NOT NULL,
    entity_type             VARCHAR(64),
    entity_id               VARCHAR(64),
    actor_id                VARCHAR(128),
    channel_used            VARCHAR(128),
    state_transition_delta  TEXT,                           -- {"from":"X","to":"Y"}
    payload_hash            VARCHAR(64),                    -- SHA-256 snapshot of the payload
    detail                  TEXT,
    correlation_id          VARCHAR(128),                   -- saga id / batch ref / idempotency key
    created_at              TIMESTAMP    NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_entity      ON audit_logs (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_correlation ON audit_logs (correlation_id);
CREATE INDEX IF NOT EXISTS idx_audit_created_at  ON audit_logs (created_at);

-- ----------------------------------------------------------------------------
-- 3. LOAN SAGA STATE (SSB Verification -> Credit Assessment -> Disbursement)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS loan_saga (
    id                  BIGSERIAL    PRIMARY KEY,
    loan_id             BIGINT       NOT NULL UNIQUE,       -- one saga per loan
    current_state       VARCHAR(40)  NOT NULL,
    failure_reason      VARCHAR(512),
    compensated_at      TIMESTAMP,
    last_transition_at  TIMESTAMP    NOT NULL,
    created_at          TIMESTAMP    NOT NULL,
    version             BIGINT                              -- optimistic lock for the reconciler
);

CREATE INDEX IF NOT EXISTS idx_loan_saga_state ON loan_saga (current_state);

-- ----------------------------------------------------------------------------
-- 4. SEQUENTIAL PUBLIC LOAN REFERENCE  (LN-2026-00042)
--    Additive: the internal %09d reference used by Ndasenda is unchanged.
-- ----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS loan_public_ref_seq START WITH 1;

ALTER TABLE loan_request ADD COLUMN IF NOT EXISTS public_reference VARCHAR(20);

CREATE UNIQUE INDEX IF NOT EXISTS uq_loan_public_reference
    ON loan_request (public_reference) WHERE public_reference IS NOT NULL;

-- ----------------------------------------------------------------------------
-- 5. PENDING-APPLICATION CHECK
--    Every application looks up the applicant's loans by upper(ec_number) and by
--    upper(national_id_number); the plain ec_number index cannot serve upper().
--    The application creates these at startup (LoanDuplicateCheckIndexes).
-- ----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_loan_request_upper_ec_number ON loan_request (upper(ec_number));
CREATE INDEX IF NOT EXISTS idx_loan_request_upper_national_id ON loan_request (upper(national_id_number));

-- ----------------------------------------------------------------------------
-- 6. RETIRED TABLES
--    idempotency_records (an idempotency engine nothing called) and
--    bulk_ingestion_runs (bulk loan upload) are no longer used. ddl-auto never
--    drops a table, so they stay, unread, until you drop them. Keep them while
--    their rows are worth looking at; then:
--
--    DROP TABLE IF EXISTS idempotency_records;
--    DROP TABLE IF EXISTS bulk_ingestion_runs;
--
--    Likewise "user".agent_id and loan_request.agent_id, which linked a sales
--    consultant to their agent and are no longer mapped:
--
--    ALTER TABLE loan_request DROP COLUMN IF EXISTS agent_id;
--    ALTER TABLE "user" DROP COLUMN IF EXISTS agent_id;
-- ----------------------------------------------------------------------------
