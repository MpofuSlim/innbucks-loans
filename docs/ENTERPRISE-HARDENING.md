# Enterprise Fintech Hardening — Architecture Notes

Additive resilience/security layer over the existing (unchanged) loan flow.
The approval, disbursement and status-check jobs keep working exactly as
before; these primitives observe, guard and formalise around them.

## 1. Append-only ledger + audit trail

`ledger_entries`: immutable double-entry legs; balances are **derived sums**,
never stored/updated. Three immutability layers: no setters, Hibernate
`@Immutable`, and a Postgres trigger rejecting UPDATE/DELETE
(`docs/db/enterprise_hardening.sql`). Postings are idempotent per
`transaction_ref`. `audit_logs` records `channel_used`, `actor_id`,
`state_transition_delta` and a `payload_hash` snapshot, written REQUIRES_NEW
so evidence survives business rollbacks.

## 2. Saga: SSB Verification → Credit Assessment → Disbursement

`LoanSagaOrchestrator` (profile `scheduled-tasks`, like the existing jobs)
projects the loan's existing status columns onto the state machine
(`LoanSagaStateResolver`) each minute and records every FORWARD transition.
Entry actions:

- **DISBURSED** → post `DISB-<ref>` (DEBIT `LOAN_PRINCIPAL_RECEIVABLE` at the
  principal / CREDIT `DISBURSEMENT_CLEARING` at the payout / CREDIT
  `FEE_INCOME` at the admin fee), assign the `LN-YYYY-XXXXX` public
  reference.
- **DISBURSEMENT_FAILED** → explicit compensation routing: if a disbursement
  was ever posted, post the reversing pair `DISB-REV-<ref>` (history keeps
  both); transition to `COMPENSATED` with a full audit trail. A crash between
  failure and compensation is replayed by the next tick (optimistic-locked
  saga row + idempotent postings).

The `LN-YYYY-XXXXX` reference is **additive** — the internal `%09d` reference
used by the Ndasenda integration is untouched.

## Operational notes

- Tables auto-create via `ddl-auto=update`; apply
  `docs/db/enterprise_hardening.sql` for the ledger trigger, the public-reference
  sequence and the partial and expression indexes (the ORM cannot express those).
