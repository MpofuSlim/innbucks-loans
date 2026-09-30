-- Payout authorisation (FR-SSB-018): approval does not pay. A loan Credit has approved is not booked with InnBucks,
-- which pays it out, until someone who works this stage authorises the payout (CLEARED), or declines it. Whoever
-- approved the loan at Credit cannot do either, nor be given its item.
--
-- It is a checkpoint at BEFORE_BOOKING, so the policy is configured like any checkpoint's, without a release: who
-- authorises (its WORK roles; FINANCE here), which loans (a minimum principal or channels; every loan here), its
-- service level and escalation. It ships switched OFF, so deploying this changes nothing: an administrator switches
-- it on (PUT /workflow-stages/PAYOUT_AUTHORISATION with "active": true) once the policy is agreed and whoever
-- authorises is ready, and from then on no loan it applies to is paid without it.
--
-- Added only if no stage already has the code, so a checkpoint an administrator created under it is left as it is.
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM workflow_stages WHERE code = 'PAYOUT_AUTHORISATION') THEN
            INSERT INTO workflow_stages (code, kind, name, description, display_order, assignment, target_hours,
                                         escalation_hours, notify_assignee, updated_by, updated_at, hold_point,
                                         minimum_principal, active, active_since)
            VALUES ('PAYOUT_AUTHORISATION', 'CHECKPOINT', 'Payout authorisation',
                    'Authorise the payout of a loan Credit has approved, or decline it', 35, 'OPTIONAL', 4, 8, TRUE,
                    'system', now() AT TIME ZONE 'UTC', 'BEFORE_BOOKING', NULL, FALSE, now() AT TIME ZONE 'UTC');

            INSERT INTO workflow_stage_roles (stage_code, user_group, entitlement)
            VALUES ('PAYOUT_AUTHORISATION', 'CREDIT_MANAGER', 'VIEW'),
                   ('PAYOUT_AUTHORISATION', 'FINANCE', 'VIEW'),
                   ('PAYOUT_AUTHORISATION', 'FINANCE', 'WORK'),
                   ('PAYOUT_AUTHORISATION', 'FINANCE', 'ASSIGN');

            INSERT INTO workflow_stage_escalation_roles (stage_code, user_group)
            VALUES ('PAYOUT_AUTHORISATION', 'SUPER_ADMIN'),
                   ('PAYOUT_AUTHORISATION', 'FINANCE');
        END IF;
    END
$$;
