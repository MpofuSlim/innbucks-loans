-- The configurable workflow (FR-SSB-014): each stage an application waits at for a person, with who may see and work
-- its queue, its service level and escalation rule, and whether its items are assigned to someone. Changed by an
-- administrator without a release. The stages themselves, and what moves a loan in or out of one, are code: each is
-- a control enforced where the loan's state changes.
CREATE TABLE workflow_stages (
    code             VARCHAR(40)  NOT NULL,
    kind             VARCHAR(16)  NOT NULL,
    name             VARCHAR(80)  NOT NULL,
    description      VARCHAR(500),
    display_order    INTEGER      NOT NULL,
    assignment       VARCHAR(16)  NOT NULL,
    target_hours     INTEGER      NOT NULL,
    escalation_hours INTEGER,
    notify_assignee  BOOLEAN      NOT NULL,
    updated_by       VARCHAR(255) NOT NULL,
    updated_at       TIMESTAMP(6) NOT NULL,
    CONSTRAINT workflow_stages_pkey PRIMARY KEY (code),
    CONSTRAINT ck_workflow_stages_kind CHECK (kind IN ('SYSTEM')),
    CONSTRAINT ck_workflow_stages_assignment CHECK (assignment IN ('NONE', 'OPTIONAL', 'EXCLUSIVE')),
    CONSTRAINT ck_workflow_stages_target CHECK (target_hours > 0),
    -- No escalation point means the stage is never escalated; one must not come before the target.
    CONSTRAINT ck_workflow_stages_escalation CHECK (escalation_hours IS NULL OR escalation_hours >= target_hours)
);

-- Who may see a stage's queue (VIEW), act on its items (WORK) and assign them to others (ASSIGN). SUPER_ADMIN holds
-- all three on every stage and is not listed; AGENTS originate applications and can hold none of them.
CREATE TABLE workflow_stage_roles (
    stage_code  VARCHAR(40) NOT NULL,
    user_group  VARCHAR(32) NOT NULL,
    entitlement VARCHAR(16) NOT NULL,
    CONSTRAINT workflow_stage_roles_pkey PRIMARY KEY (stage_code, user_group, entitlement),
    CONSTRAINT fk_workflow_stage_roles_stage FOREIGN KEY (stage_code) REFERENCES workflow_stages (code),
    CONSTRAINT ck_workflow_stage_roles_group CHECK (user_group IN ('CREDIT_MANAGER', 'FINANCE')),
    CONSTRAINT ck_workflow_stage_roles_entitlement CHECK (entitlement IN ('VIEW', 'WORK', 'ASSIGN'))
);

-- Who is emailed when an item in the stage is escalated.
CREATE TABLE workflow_stage_escalation_roles (
    stage_code VARCHAR(40) NOT NULL,
    user_group VARCHAR(32) NOT NULL,
    CONSTRAINT workflow_stage_escalation_roles_pkey PRIMARY KEY (stage_code, user_group),
    CONSTRAINT fk_workflow_stage_escalation_roles_stage FOREIGN KEY (stage_code) REFERENCES workflow_stages (code),
    CONSTRAINT ck_workflow_stage_escalation_roles_group CHECK (user_group IN ('SUPER_ADMIN', 'CREDIT_MANAGER', 'FINANCE'))
);

-- The defaults reproduce who could do what before this change. The credit decision keeps the service level it was
-- given (V11); the other stages get one for the bank to change.
INSERT INTO workflow_stages (code, kind, name, description, display_order, assignment, target_hours, escalation_hours,
                             notify_assignee, updated_by, updated_at)
SELECT 'CREDIT_DECISION', 'SYSTEM', 'Credit decision',
       'Approve, reject or return an application SSB has accepted', 20, 'OPTIONAL', target_hours, escalation_hours,
       TRUE, 'system', now() AT TIME ZONE 'UTC'
FROM service_levels
WHERE stage = 'CREDIT_DECISION';

INSERT INTO workflow_stages (code, kind, name, description, display_order, assignment, target_hours, escalation_hours,
                             notify_assignee, updated_by, updated_at)
SELECT 'CREDIT_DECISION', 'SYSTEM', 'Credit decision',
       'Approve, reject or return an application SSB has accepted', 20, 'OPTIONAL', 24, 48, TRUE, 'system',
       now() AT TIME ZONE 'UTC'
WHERE NOT EXISTS (SELECT 1 FROM workflow_stages WHERE code = 'CREDIT_DECISION');

INSERT INTO workflow_stages (code, kind, name, description, display_order, assignment, target_hours, escalation_hours,
                             notify_assignee, updated_by, updated_at)
VALUES ('PAYSLIP_REVIEW', 'SYSTEM', 'Payslip review',
        'Clear or confirm an application held for a payslip finding', 10, 'OPTIONAL', 24, 48, TRUE, 'system',
        now() AT TIME ZONE 'UTC'),
       ('MORE_INFORMATION', 'SYSTEM', 'More information',
        'The originator answers a return from Credit', 30, 'NONE', 48, 96, TRUE, 'system', now() AT TIME ZONE 'UTC'),
       ('EMPLOYMENT_EVENT_REVIEW', 'SYSTEM', 'Employment event review',
        'Release or decline an application held for an employment event, or review a paid loan', 40, 'OPTIONAL', 48,
        96, TRUE, 'system', now() AT TIME ZONE 'UTC'),
       ('DEDUCTION_CANCELLATION', 'SYSTEM', 'Deduction cancellation',
        'Cancel the SSB deduction of a loan that will not be paid, and record it', 50, 'OPTIONAL', 24, 48, TRUE,
        'system', now() AT TIME ZONE 'UTC');

INSERT INTO workflow_stage_roles (stage_code, user_group, entitlement)
VALUES ('PAYSLIP_REVIEW', 'CREDIT_MANAGER', 'VIEW'),
       ('PAYSLIP_REVIEW', 'CREDIT_MANAGER', 'WORK'),
       ('PAYSLIP_REVIEW', 'CREDIT_MANAGER', 'ASSIGN'),
       ('CREDIT_DECISION', 'CREDIT_MANAGER', 'VIEW'),
       ('CREDIT_DECISION', 'CREDIT_MANAGER', 'WORK'),
       ('CREDIT_DECISION', 'CREDIT_MANAGER', 'ASSIGN'),
       ('MORE_INFORMATION', 'CREDIT_MANAGER', 'VIEW'),
       ('EMPLOYMENT_EVENT_REVIEW', 'CREDIT_MANAGER', 'VIEW'),
       ('EMPLOYMENT_EVENT_REVIEW', 'FINANCE', 'VIEW'),
       ('EMPLOYMENT_EVENT_REVIEW', 'CREDIT_MANAGER', 'WORK'),
       ('EMPLOYMENT_EVENT_REVIEW', 'CREDIT_MANAGER', 'ASSIGN'),
       ('DEDUCTION_CANCELLATION', 'CREDIT_MANAGER', 'VIEW'),
       ('DEDUCTION_CANCELLATION', 'FINANCE', 'VIEW'),
       ('DEDUCTION_CANCELLATION', 'FINANCE', 'WORK'),
       ('DEDUCTION_CANCELLATION', 'FINANCE', 'ASSIGN');

INSERT INTO workflow_stage_escalation_roles (stage_code, user_group)
SELECT code, 'SUPER_ADMIN' FROM workflow_stages;

-- One loan's wait at one stage, once someone has assigned or escalated it: identified by when the wait began, so a
-- loan that comes back to a stage starts a fresh item. Assignment changes in place; how it changed is in
-- work_item_events.
CREATE TABLE work_items (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY,
    stage_code   VARCHAR(40)  NOT NULL,
    loan_id      BIGINT       NOT NULL,
    entered_at   TIMESTAMP(6) NOT NULL,
    assigned_to  VARCHAR(100),
    assigned_at  TIMESTAMP(6),
    escalated_at TIMESTAMP(6),
    created_at   TIMESTAMP(6) NOT NULL,
    CONSTRAINT work_items_pkey PRIMARY KEY (id),
    CONSTRAINT fk_work_items_stage FOREIGN KEY (stage_code) REFERENCES workflow_stages (code),
    CONSTRAINT fk_work_items_loan_id FOREIGN KEY (loan_id) REFERENCES loans (id),
    CONSTRAINT uq_work_items_wait UNIQUE (stage_code, loan_id, entered_at),
    CONSTRAINT ck_work_items_assignment CHECK ((assigned_to IS NULL) = (assigned_at IS NULL))
);
CREATE INDEX idx_work_items_loan_id ON work_items (loan_id);
CREATE INDEX idx_work_items_assigned_to ON work_items (assigned_to) WHERE assigned_to IS NOT NULL;

-- Every assignment, reassignment, release and escalation, as it happened. Append-only.
CREATE TABLE work_item_events (
    id           BIGINT GENERATED BY DEFAULT AS IDENTITY,
    work_item_id BIGINT       NOT NULL,
    action       VARCHAR(16)  NOT NULL,
    from_user    VARCHAR(100),
    to_user      VARCHAR(100),
    performed_by VARCHAR(255) NOT NULL,
    performed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT work_item_events_pkey PRIMARY KEY (id),
    CONSTRAINT fk_work_item_events_work_item FOREIGN KEY (work_item_id) REFERENCES work_items (id),
    CONSTRAINT ck_work_item_events_action CHECK (action IN ('ASSIGNED', 'REASSIGNED', 'RELEASED', 'ESCALATED'))
);
CREATE INDEX idx_work_item_events_work_item_id ON work_item_events (work_item_id);
CREATE TRIGGER trg_work_item_events_append_only
    BEFORE UPDATE OR DELETE ON work_item_events
    FOR EACH ROW EXECUTE FUNCTION refuse_append_only_change();
CREATE TRIGGER trg_work_item_events_no_truncate
    BEFORE TRUNCATE ON work_item_events
    FOR EACH STATEMENT EXECUTE FUNCTION refuse_append_only_change();

-- Credit decisions already escalated (V11) become escalated work items, and the stages take over the service levels.
INSERT INTO work_items (stage_code, loan_id, entered_at, escalated_at, created_at)
SELECT 'CREDIT_DECISION', id, coalesce(credit_resubmitted_at, date_approved), credit_escalated_at, credit_escalated_at
FROM loans
WHERE credit_escalated_at IS NOT NULL
  AND coalesce(credit_resubmitted_at, date_approved) IS NOT NULL;

INSERT INTO work_item_events (work_item_id, action, performed_by, performed_at)
SELECT id, 'ESCALATED', 'credit-escalation-job', escalated_at
FROM work_items
WHERE escalated_at IS NOT NULL;

ALTER TABLE loans DROP COLUMN credit_escalated_at;
DROP TABLE service_levels;
