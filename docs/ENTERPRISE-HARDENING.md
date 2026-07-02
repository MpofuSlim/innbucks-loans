# Enterprise Fintech Hardening — Architecture Notes

Additive resilience/security layer over the existing (unchanged) loan flow.
The approval, disbursement and status-check jobs keep working exactly as
before; these primitives observe, guard and formalise around them.

## 1. Idempotency engine + cross-channel HMAC (Stripe standard)

**Java:** `ChannelSecurityFilter` (registered in `loans-api` only) →
`IdempotencyService` (DB-arbitrated, `idempotency_records` PK is the
cross-node duplicate lock) + `HmacSignatureVerifier` + `ReplayNonceCache` +
`VelocityRateLimiter`.

Headers on every mutating call:

| Header | Value |
|---|---|
| `Idempotency-Key` | UUIDv4 per logical operation (reused on retries) |
| `X-Channel-Id` | registered channel identifier |
| `X-Channel-Timestamp` | epoch millis (±5 min skew) |
| `X-Channel-Nonce` | random 128-bit hex, single-use |
| `X-Channel-Signature` | `HMAC_SHA256(secret, channelId\nts\nnonce\nsha256Hex(body))` |

Retried call with a completed key → the **identical cached response** is
replayed (`X-Idempotent-Replay: true`); business logic never re-executes.
Key reused with a different payload → 422. In-flight duplicate → 409.
5xx outcomes release the key so retries re-execute.

**Rollout:** `channel-security.mode` = `DISABLED` | `MONITOR` (default) |
`ENFORCE`. MONITOR verifies + audits + replays but never rejects — flip to
ENFORCE per environment once the gateways sign requests. The signing
contract for the gateway team is `docs/integration/channel-signing.js`; the
canonical string is pinned by `HmacSignatureVerifierTest` on the Java side.

## 2. Chunked bulk pipeline (`POST /api/loans/bulk`)

`BulkLoanIngestionService`: payload → chunks of `bulk-ingestion.chunk-size`
(100) → virtual-thread workers → each item in its **own `REQUIRES_NEW`
transaction** (`BulkLoanItemProcessor`). Item #14 failing on validation or an
Ndasenda/InnBucks outage is trapped, audited (`BULK_ITEM_REJECTED` with error
context + payload hash), and the rest of the batch continues. Each item flows
through the existing `LoanService.requestLoan` — bulk is orchestration, not a
second loan path. Uploaded documents are magic-number-validated
(`FileSignatureValidator`) before touching business state.

## 3. Append-only ledger + audit trail

`ledger_entries`: immutable double-entry legs; balances are **derived sums**,
never stored/updated. Three immutability layers: no setters, Hibernate
`@Immutable`, and a Postgres trigger rejecting UPDATE/DELETE
(`docs/db/enterprise_hardening.sql`). Postings are idempotent per
`transaction_ref`. `audit_logs` records `channel_used`, `actor_id`,
`state_transition_delta` and a `payload_hash` snapshot, written REQUIRES_NEW
so evidence survives business rollbacks.

## 4. Saga: SSB Verification → Credit Assessment → Disbursement

`LoanSagaOrchestrator` (profile `scheduled-tasks`, like the existing jobs)
projects the loan's existing status columns onto the state machine
(`LoanSagaStateResolver`) each minute and records every FORWARD transition.
Entry actions:

- **DISBURSED** → post `DISB-<ref>` (DEBIT `LOAN_PRINCIPAL_RECEIVABLE` /
  CREDIT `DISBURSEMENT_CLEARING`), assign the `LN-YYYY-XXXXX` public
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
  `docs/db/enterprise_hardening.sql` for the ledger trigger + partial index
  (the ORM cannot express those).
- New env knobs: `CHANNEL_SECURITY_MODE`, `CHANNEL_SECURITY_VELOCITY_LIMIT`,
  `BULK_INGESTION_CHUNK_SIZE`, per-channel `channel-security.secrets.*`.
