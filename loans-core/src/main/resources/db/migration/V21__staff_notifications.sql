-- Staff Grocery Loan notifications (FR-SGL-019 to FR-SGL-024).
--
-- A notification is one message to one staff member: about an offer a weekly run issued them, new or refreshed, or the
-- one-off launch broadcast. It is kept here, which is the member's in-app inbox until the SuperApp has an endpoint to
-- read it from, and is also sent to their phone: by SMS through the InnBucks notification API, and by WhatsApp when the
-- SMS fails. Every attempt on every channel is logged (staff_notification_dispatches), sent or not.
--
-- A notification is created in the same transaction as the offer or broadcast it is about, at most once for each
-- (unique indexes below), so running again never tells anyone twice. It is sent after that transaction commits, and is
-- claimed (PENDING to SENDING) before it is sent, so it is never sent twice either.

CREATE TABLE staff_notification_broadcasts (
    id               BIGSERIAL    NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    template         VARCHAR(32)  NOT NULL,
    template_version INTEGER      NOT NULL,
    -- Members of the register told, and those left out because they have left the bank.
    recipients       INTEGER      NOT NULL,
    left_excluded    INTEGER      NOT NULL,
    created_by       VARCHAR(255) NOT NULL,
    created_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT staff_notification_broadcasts_pkey PRIMARY KEY (id),
    CONSTRAINT ck_staff_notification_broadcasts_kind CHECK (kind IN ('LAUNCH')),
    CONSTRAINT ck_staff_notification_broadcasts_counts CHECK (recipients >= 0 AND left_excluded >= 0)
);
-- The launch is announced once (FR-SGL-020).
CREATE UNIQUE INDEX uq_staff_notification_broadcasts_kind ON staff_notification_broadcasts (kind);

CREATE TABLE staff_notifications (
    id                BIGSERIAL    NOT NULL,
    staff_member_id   BIGINT       NOT NULL,
    template          VARCHAR(32)  NOT NULL,
    template_version  INTEGER      NOT NULL,
    offer_id          BIGINT,
    run_id            BIGINT,
    broadcast_id      BIGINT,
    title             VARCHAR(120) NOT NULL,
    message           TEXT         NOT NULL,
    created_at        TIMESTAMP(6) NOT NULL,
    -- The message to the member's phone: PENDING until it is claimed, SENDING while it is being sent, then SENT,
    -- FAILED (every channel failed) or SKIPPED (not sent, for skip_reason).
    outbound_status   VARCHAR(16)  NOT NULL,
    skip_reason       VARCHAR(32),
    delivered_channel VARCHAR(16),
    claimed_at        TIMESTAMP(6),
    finished_at       TIMESTAMP(6),
    version           BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_notifications_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_notifications_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT fk_staff_notifications_offer FOREIGN KEY (offer_id) REFERENCES staff_offers (id),
    CONSTRAINT fk_staff_notifications_run FOREIGN KEY (run_id) REFERENCES staff_offer_runs (id),
    CONSTRAINT fk_staff_notifications_broadcast
        FOREIGN KEY (broadcast_id) REFERENCES staff_notification_broadcasts (id),
    CONSTRAINT ck_staff_notifications_template CHECK (template IN ('OFFER_NEW', 'OFFER_REFRESHED', 'LAUNCH')),
    -- An offer notification names its offer and run; a broadcast's names its broadcast.
    CONSTRAINT ck_staff_notifications_subject
        CHECK ((template IN ('OFFER_NEW', 'OFFER_REFRESHED')) = (offer_id IS NOT NULL)
            AND (offer_id IS NULL) = (run_id IS NULL)
            AND (template = 'LAUNCH') = (broadcast_id IS NOT NULL)),
    CONSTRAINT ck_staff_notifications_outbound_status
        CHECK (outbound_status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_staff_notifications_skip_reason
        CHECK ((outbound_status = 'SKIPPED') = (skip_reason IS NOT NULL)
            AND (skip_reason IS NULL OR skip_reason IN ('OPTED_OUT', 'FREQUENCY_CAP', 'OFFER_CLOSED'))),
    CONSTRAINT ck_staff_notifications_delivered_channel
        CHECK ((outbound_status = 'SENT') = (delivered_channel IS NOT NULL)
            AND (delivered_channel IS NULL OR delivered_channel IN ('SMS', 'WHATSAPP'))),
    CONSTRAINT ck_staff_notifications_claimed
        CHECK ((outbound_status = 'PENDING') = (claimed_at IS NULL)
            AND (outbound_status IN ('PENDING', 'SENDING')) = (finished_at IS NULL))
);
-- At most one notification per offer, and one per member per broadcast (FR-SGL-024).
CREATE UNIQUE INDEX uq_staff_notifications_offer ON staff_notifications (offer_id) WHERE offer_id IS NOT NULL;
CREATE UNIQUE INDEX uq_staff_notifications_broadcast ON staff_notifications (broadcast_id, staff_member_id)
    WHERE broadcast_id IS NOT NULL;
CREATE INDEX ix_staff_notifications_member ON staff_notifications (staff_member_id, id);
CREATE INDEX ix_staff_notifications_run ON staff_notifications (run_id) WHERE run_id IS NOT NULL;
CREATE INDEX ix_staff_notifications_pending ON staff_notifications (id) WHERE outbound_status = 'PENDING';

-- Every attempt to reach a member, on each channel (FR-SGL-023): IN_APP when the notification is stored, then SMS,
-- and WhatsApp when the SMS failed. Never changed once written.
CREATE TABLE staff_notification_dispatches (
    id                BIGSERIAL    NOT NULL,
    notification_id   BIGINT       NOT NULL,
    staff_member_id   BIGINT       NOT NULL,
    channel           VARCHAR(16)  NOT NULL,
    recipient         VARCHAR(32)  NOT NULL,
    template          VARCHAR(32)  NOT NULL,
    template_version  INTEGER      NOT NULL,
    status            VARCHAR(16)  NOT NULL,
    -- Our reference for an SMS at the notification API; the WhatsApp gateway takes none.
    gateway_reference VARCHAR(64),
    failure_reason    VARCHAR(255),
    attempted_at      TIMESTAMP(6) NOT NULL,
    CONSTRAINT staff_notification_dispatches_pkey PRIMARY KEY (id),
    CONSTRAINT fk_staff_notification_dispatches_notification
        FOREIGN KEY (notification_id) REFERENCES staff_notifications (id),
    CONSTRAINT fk_staff_notification_dispatches_member FOREIGN KEY (staff_member_id) REFERENCES staff_members (id),
    CONSTRAINT ck_staff_notification_dispatches_channel CHECK (channel IN ('IN_APP', 'SMS', 'WHATSAPP')),
    CONSTRAINT ck_staff_notification_dispatches_status
        CHECK (status IN ('STORED', 'SENT', 'FAILED') AND (status = 'STORED') = (channel = 'IN_APP')),
    CONSTRAINT ck_staff_notification_dispatches_failure CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT ck_staff_notification_dispatches_reference
        CHECK ((channel = 'SMS') = (gateway_reference IS NOT NULL))
);
CREATE INDEX ix_staff_notification_dispatches_notification ON staff_notification_dispatches (notification_id);
CREATE INDEX ix_staff_notification_dispatches_attempted ON staff_notification_dispatches (attempted_at);

-- A member's choice not to be sent offer messages (FR-SGL-022). Offers are still made to them and still appear in
-- their in-app inbox, so they can still apply; only the SMS and WhatsApp messages stop. No row: they receive them.
CREATE TABLE staff_notification_preferences (
    staff_member_id          BIGINT       NOT NULL,
    offer_messages_opted_out BOOLEAN      NOT NULL,
    reason                   VARCHAR(255) NOT NULL,
    updated_by               VARCHAR(255) NOT NULL,
    updated_at               TIMESTAMP(6) NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT staff_notification_preferences_pkey PRIMARY KEY (staff_member_id),
    CONSTRAINT fk_staff_notification_preferences_member
        FOREIGN KEY (staff_member_id) REFERENCES staff_members (id)
);
