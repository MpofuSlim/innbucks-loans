-- Staff Grocery Loan borrowers sign in through the SuperApp (FR-SGL-025): the InnBucks middleware, which checks their
-- PIN, signs a short-lived assertion naming their phone, and loans trades it for a session of its own. An assertion is
-- good once: its jti is recorded here when it is used, so a captured one cannot be replayed. A row is kept a day past
-- the assertion's expiry, after which the assertion is refused as expired anyway, and then deleted.
CREATE TABLE borrower_assertion_uses (
    jti             VARCHAR(128) NOT NULL,
    purpose         VARCHAR(16)  NOT NULL,
    staff_member_id BIGINT,
    used_at         TIMESTAMP(6) NOT NULL,
    expires_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT borrower_assertion_uses_pkey PRIMARY KEY (jti),
    CONSTRAINT fk_borrower_assertion_uses_staff_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT ck_borrower_assertion_uses_purpose CHECK (purpose IN ('SIGN_IN', 'STEP_UP'))
);
CREATE INDEX ix_borrower_assertion_uses_expires_at ON borrower_assertion_uses (expires_at);
