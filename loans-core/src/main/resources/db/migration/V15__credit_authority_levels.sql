-- Credit approval limits and referral (FR-PBL-028, which FR-SSB-015 applies to SSB loans). An administrator defines
-- authority levels, each with the largest principal it may approve (none: any amount), and gives credit officers a
-- level each. Once any level exists, an officer approves only loans within their level; a loan above it is referred to
-- a higher level with the officer's recommendation. SUPER_ADMIN may approve any amount. While no level exists, nothing
-- is limited, as before.
CREATE TABLE credit_authority_levels (
    code              VARCHAR(40)    NOT NULL,
    name              VARCHAR(80)    NOT NULL,
    maximum_principal NUMERIC(19, 2),
    updated_by        VARCHAR(255)   NOT NULL,
    updated_at        TIMESTAMP(6)   NOT NULL,
    CONSTRAINT credit_authority_levels_pkey PRIMARY KEY (code),
    CONSTRAINT ck_credit_authority_levels_maximum_principal
        CHECK (maximum_principal IS NULL OR maximum_principal > 0),
    -- Levels are ranked by their limit, so no two share one, and only one has none.
    CONSTRAINT uq_credit_authority_levels_maximum_principal UNIQUE NULLS NOT DISTINCT (maximum_principal)
);

-- Each user's level, if they have one.
ALTER TABLE users ADD COLUMN credit_authority_level VARCHAR(40);
ALTER TABLE users ADD CONSTRAINT fk_users_credit_authority_level
    FOREIGN KEY (credit_authority_level) REFERENCES credit_authority_levels (code);
CREATE INDEX idx_users_credit_authority_level ON users (credit_authority_level);

-- A referral in the credit decision log: the level it went to (SUPER_ADMIN when it is above every level) and what the
-- officer who referred it recommends. The reason code is one of that recommendation's.
ALTER TABLE credit_decisions DROP CONSTRAINT ck_credit_decisions_action;
ALTER TABLE credit_decisions ADD CONSTRAINT ck_credit_decisions_action
    CHECK (action IN ('APPROVED', 'REJECTED', 'RETURNED', 'RESUBMITTED', 'REFERRED'));
ALTER TABLE credit_decisions ADD COLUMN referred_to VARCHAR(40);
ALTER TABLE credit_decisions ADD COLUMN recommendation VARCHAR(16);
ALTER TABLE credit_decisions ADD CONSTRAINT ck_credit_decisions_recommendation
    CHECK (recommendation IN ('APPROVED', 'REJECTED'));
ALTER TABLE credit_decisions ADD CONSTRAINT ck_credit_decisions_referral
    CHECK ((action = 'REFERRED') = (referred_to IS NOT NULL) AND (action = 'REFERRED') = (recommendation IS NOT NULL));
