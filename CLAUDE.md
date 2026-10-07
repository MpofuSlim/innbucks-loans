# innbucks-loans (loans-service) — Claude memory

How loans joins the fleet (gateway routes, k8s, env, scheduled jobs OFF in the
cell) is in `docs/fleet-wiring.md`; read it first.

## Upstream tokens: never hold a lock across a login

**A cached upstream bearer token goes through `SingleFlightTokenCache`
(`loans-core/.../config/`), never a `synchronized` method around the login.**
The notification API client (`NotificationApiAuthenticator`) used to be
`synchronized currentToken(...)`, which held its monitor for the whole
`POST /auth/third-party`: one slow login (up to connect + read timeout) stalled
every SMS and email, including senders already holding a good token. The same
shape is used in ticketing, InnRewards and market-place; keep them alike.

- Fast path is lock-free (`AtomicReference` of token + expiry); at most one
  login runs at a time and the lock is held only to start or join its
  `CompletableFuture`; inside the refresh margin, callers that did not start
  the refresh keep the current token; a caller with no usable token waits at
  most connect + read timeout + 2 s, then gets the client's own transient
  exception (`NotificationDeliveryException`).
- After a 401 call `refreshAfterRejection(theTokenThatWasRefused)`, never a
  plain "force": it logs in only if that token is still the cached one, so N
  concurrent 401s cost one login.
- A failed login caches nothing and the next caller retries. Never log the
  token, the credentials or a refused login's body.
- `InnbucksAuthService` and `NdasendaAuthService` are Spring `@Cacheable`
  (Caffeine, no TTL, evicted on 401) and hold no lock, so they do not have this
  problem. Their `refreshToken()` calls `getAccessToken()` on itself, which
  bypasses the cache proxy: the token it returns is used once and NOT cached
  (the `@CacheEvict` runs after it), so the next call logs in again. Harmless
  while their callers are the serial, single-threaded `scheduled-tasks` jobs
  (OFF in the cell) and the manual payout; move them onto
  `SingleFlightTokenCache` before either gains concurrent callers.

Pinned by `SingleFlightTokenCacheTest` and
`NotificationApiAuthenticatorConcurrencyTest`.

## Tracing

**Distributed tracing is Micrometer Tracing over OpenTelemetry, with W3C
`traceparent`, the same convention in every fleet service (ticketing-system,
InnRewards, market-place).** The gateway starts the trace; loans continues it,
logs it and never hands it to a partner. **Spans are exported only when a
collector is named.** Wiring is in `loans-api` (`config/TracingConfig`), the
log pattern in `loans-core/src/main/resources/logback.xml`.

- **Dependencies** (`loans-api/pom.xml`): `spring-boot-micrometer-tracing-opentelemetry`
  (Boot 4's tracing auto-configuration), `micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp`, all Boot-managed. **Not**
  `spring-boot-starter-opentelemetry`: it also brings `micrometer-registry-otlp`,
  which pushes metrics to `localhost:4318` and logs a failure every step.
- **Env** — `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` (blank = export nothing) and
  `TRACING_SAMPLING_PROBABILITY` (default `0.1`; parent-based, so the gateway's
  sampled flag wins). **The loans Deployment has no `envFrom` of the cell
  config**, so each needs its own explicit `env:` entry in ticketing-system's
  `deploy/k8s/loans/loans-service.yaml`, naming the `cell-zw` ConfigMap key
  (`optional: true`). Boot maps the endpoint variable itself — falling back to
  `OTEL_EXPORTER_OTLP_ENDPOINT` — and skips an EMPTY one. Never put either
  variable in `loans.example.env`/the loans Secret: they are cell-wide.
- **Never put the endpoint in `application.yml`.** `endpoint:
  ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:}` defines the property as `""`, which
  satisfies the exporter's `@ConditionalOnProperty` and builds an exporter with
  no URL. `TracingExportGateTest` fails if the key appears.
- **`management.tracing.export.enabled` stays `true`.** In Boot 4 `false` does
  not just stop export — it swaps in a no-op propagator, so the gateway's
  `traceparent` would be ignored and every request would start a new trace.
  Export is switched off by the missing endpoint, never by this.
- **Exported spans are named `loans-service`**
  (`management.opentelemetry.resource-attributes`): loans sets no
  `spring.application.name`.
- **Logs.** The console pattern prints `[traceId,spanId]` from the MDC on every
  line written inside a request or an `@Async` task (`[,]` outside one). Loans
  has one log format; there is no JSON appender here. Logs themselves are never
  exported over OTLP (`management.logging.export.otlp.enabled: false`), even if
  the generic `OTEL_EXPORTER_OTLP_ENDPOINT` is set.
- **Partners NEVER get the trace — enforced by destination, not by client.**
  Loans calls no fleet service; every outbound call is a partner's (InnBucks
  booking/deposit/login and Ndasenda over the shared `RestTemplate`; the
  notification API and WhatsApp over `RestClient`; SES is SMTP). Boot's sender
  handler is replaced by one over `FleetOnlyPropagator`, which writes trace
  headers only to a host that is a fleet Service name (`*-service`, no dot), so
  even a partner client built from an observed builder sends nothing. Partner
  WAFs have refused unknown headers before. The WhatsApp and InnBucks deposit
  contract tests pin `withoutHeader("traceparent")`.
- **`@Async` keeps the trace.** Loans has no executor of its own: `@Async` runs
  on Boot's `applicationTaskExecutor`, and Boot applies the `TaskDecorator`
  bean `TracingConfig.traceContextTaskDecorator()` to it — a
  `ContextPropagatingTaskDecorator` scoped to the OBSERVATION only, because the
  default one also snapshots Spring Security's context and would hand the
  caller's authentication to the notification threads. The voucher and staff
  notification dispatchers run on their own single-thread executors and do NOT
  carry the trace yet.
- **No response compression here.** It is done once at the ticketing
  api-gateway / edge; compressing here too would double-encode.
- Pinned by `TracingTest` (packaged config, real HTTP: the gateway's
  `traceparent` continued into the MDC, loans' own WhatsApp and `RestTemplate`
  factories send no trace, an observed client to a non-fleet host sends none
  while a Service name does, `@Async` keeps the trace but not the caller's
  authentication, no exporter with no endpoint), `TracingExportGateTest` and
  `FleetOnlyPropagatorTest`.

## Loan's associations are LAZY: fetch them on purpose

**`Loan.merchant`, `Loan.createdByUser`, `Loan.channel` and `Loan.payslipDeductions` are LAZY (so is
`LoanDisbursement.loan`), and `spring.jpa.open-in-view` is false.** They used to be EAGER (`@ManyToOne`'s
default), so every lock a job took, every saga step and every queue read loaded the merchant, the originator and
the channel, and behind them the originator's merchant, commission group and groups and the channel's system user,
whether anything read them or not. Lazy, a path that reads one outside a transaction throws
`LazyInitializationException`: a 500 on an endpoint, and on a job that calls InnBucks with no transaction open, a
booking held AMBIGUOUS or a payout SMS that never goes. So:

- **A read path that renders one fetches it.** The loan list and detail (`LoanRepository.findAll(spec, page)` /
  `findOne(spec)`) and the credit workbench (`findWithAssociationsById`) carry an `@EntityGraph` of the three
  to-ones, and `LoanServiceImpl.findLoans` / `getLoan` are
  `@Transactional(readOnly = true)`, so what those hold, and the payslip deductions, load inside it, in one batch
  per kind (`hibernate.default_batch_fetch_size: 100`). Every other loan read in a service is already inside a
  transaction. Never `JOIN FETCH` a collection in a paged query (Hibernate pages in memory).
- **A path that hands a loan past its transaction initialises what the later step reads, in the transaction.**
  Today: `LoanBookingJob.claim` (the booking reads the merchant after the claim commits),
  `DisbursementService.settle` (the payout SMS names the merchant after the commit), and
  `LoanDisbursementStatusJob`, which reads each chunk with `findWithMerchantByIdIn` (fetch join, no lock).
  `CheckpointGate.withoutHeld` and the read half of `WorkflowEscalationService.escalateOverdue` run in read-only
  transactions of their own, because a checkpoint reads each loan's channel. A new job that calls out with a loan
  in hand must do the same.
- **Lombok:** the four are `@ToString.Exclude @EqualsAndHashCode.Exclude`, so a log line or a hash never loads
  them. `SensitiveToStringTest` pins it.
- **Left EAGER, deliberately:** `User.merchant`, `User.commissionGroup`, `User.groups`, `Merchant.commissionGroup`
  and `Channel.systemUser`. The user is the security principal, read in controllers with no transaction open
  (`caller.getMerchant()`); they are small reference rows that batch-load. Change them only with every reader
  moved inside a transaction.
- **Pinned by `LoanFetchPlanPostgresIT`** (loans-api, real Postgres, real HTTP and token): every read path that
  renders an association, measured with Hibernate `Statistics` at 3 and at 12 loans (each with its own merchant,
  originator and channel) and required to cost the same, plus the booking, status, manual payout, escalation,
  lodgement and saga paths driven end to end. A new path that reads an association belongs in it.

## Job queues are walked in chunks (`IdChunks`), never read whole

**A scheduled job reads its queue a chunk of ids at a time, by keyset on the id** (`jobs/IdChunks`: ids above the
last one handed out, ascending, `IdChunks.SIZE` = 100 per chunk), never the whole backlog at once. The lodgement
and booking jobs (and their stale-claim sweeps), the disbursement status job and the saga reconciler do; the
voucher and staff-notification dispatchers already did.

- What it keeps: id order; the job's own stop (a handler returning false ends the walk, so a run that stops on an
  unreachable partner never reads the next chunk); each row's claim, lock, settle and retry exactly as before. What
  it guarantees: no row is handed out twice in one run, even one that failed or was skipped and is still in the
  queue (the next chunk starts strictly above it), so a failing row never stalls the walk and waits for the next
  run, as before. A row that becomes due during a run with an id above the cursor is reached in that run.
- A keyset query must filter `id > :after` and order by id; `IdChunks` refuses a chunk that does not move forward.
- Not chunked, on purpose: the weekly staff offer run (one transaction under the register lock, all or nothing;
  the register is the staff list), the arrears report (one email of every row), the Ndasenda response sweep (status
  projections, all needed to date its window) and the escalation queues (the work queues' own definition).
- Pinned by `IdChunksTest` and the chunk cases in `NdasendaLodgementJobTest`, `LoanBookingJobTest`,
  `LoanDisbursementStatusJobTest` and `LoanSagaReconcileTest`: all of a queue longer than a chunk, each row once, a
  failing row not blocking the rest.

## Aggregates and searches run in the database, once

- **The dashboard is two statements** (`DashboardServiceImpl`): one grouped scan of loans
  (`LoanRepository.dashboardGroups`: count and both sums per SSB approval × Credit decision × disbursement status)
  that every loan figure is folded from, and one statement for the merchant, user and batch counts. It was nine, five
  of them whole-table reads of loans. A new loan figure is folded from those groups (add a column to the GROUP BY or
  a sum to the select), never a query of its own. The SUCCESS sums start from `BigDecimal.ZERO` so an empty result
  is `0` and a populated one carries the column's scale, exactly as `coalesce(sum(...), 0)` did.
- **Its answer is cached 30s, one entry** (`innbucks.dashboard.cache-ttl`, zero turns it off). That is correct only
  because the dashboard is platform-wide and SUPER_ADMIN only: every caller who can reach it sees the same thing. A
  dashboard or figure scoped by caller (merchant, agent, channel) puts the scope in the key or is not cached.
- **Pages are cut by the database, never in memory.** The user search (`AuthServiceImpl.search`, no endpoint today)
  pages with a `Pageable`, in id order; it used to load every match and `skip()/limit()` them, in no defined order.
- **A substring search's trigram index must be on EXACTLY the expression the query compares (V34).** `pg_trgm` GIN
  indexes: `lower(employee_number)`, `lower(full_name)`, `lower(department)`, `msisdn` on `staff_members` (the
  register search's `cb.lower(...)` / raw msisdn) and `upper(username)` on `users` (Spring Data's
  `ContainingIgnoreCase` renders `upper(...) like upper(?)`). An index on another expression is never used, silently.
  Change the query and its index together, in a new migration. `DashboardAndSearchPostgresIT` captures the SQL
  Hibernate sends (`CapturedSql`, a `StatementInspector`), reads the LIKE operands out of it and fails if they are not
  exactly the indexed expressions, or if an index stops serving its expression. It also pins the dashboard against
  the nine old queries (kept in the test as the oracle) and the response body byte for byte.

## A status check that cannot be saved does not stop the run

`LoanDisbursementStatusJob` catches a failed save per loan (`recordUnpaid`, and the paid path's `recordPaid` as
before), logs it, counts `loans.disbursement_status.save_failed` (registered at 0) and goes on to the next loan; it
used to abort the whole run, so one loan in conflict left every loan after it unchecked. Nothing of that loan's check
is kept, its applicant is not told (the FAILED notice goes only after the save), and the next run asks InnBucks again.
The job only reads InnBucks' status: nothing it does is retried, and no paying call is involved.

## Ids: IDENTITY by default, pooled sequences where inserts come in bulk (V33)

**Under `GenerationType.IDENTITY` Hibernate cannot batch an INSERT** (it reads each generated id back), so
`hibernate.jdbc.batch_size` only ever batched UPDATEs. `StaffRegisterRow` (one per line of a register upload) and
`StaffRegisterVariance` (one per reconciliation finding) now draw ids from their own serial sequences 50 at a time
(`SEQUENCE`, `allocationSize = 50`, the pooled-lo optimizer set in `JpaSchemaConfig`), and their `saveAll` goes
out as JDBC batches.

- **V33** set each sequence's `INCREMENT BY 50` (Hibernate's schema validation checks it against `allocationSize`)
  and `setval` to at least the table's max id, never backwards. The columns keep `DEFAULT nextval(...)` on the same
  sequences, so a build from before V33 still inserts after a rollback without colliding: each of its rows takes a
  `nextval` of its own, which no pooled block was opened at. `PooledStaffRegisterIdsMigrationIT` pins all three.
- **Pooled ids are monotonic within one JVM, not across two.** Only an entity whose rows are written in one
  transaction and never ordered across writers by id may move. That is why the rest stay IDENTITY: `StaffOffer`,
  `StaffNotification` and its dispatches, `StaffMemberChange`, `LedgerEntry` and `AuditLog` are written by the API
  and the job instances alike and read "newest first" or "in order" by id; `Loan`'s id is the reference SSB and
  InnBucks hold and is inserted one at a time. Moving one of them is a decision, not a cleanup.
- A new pooled entity: sequence `INCREMENT BY` equal to `allocationSize`, `setval` above max in its migration, the
  column default left on the same sequence.

## The legacy document columns on `loans` are dormant (V6, V35)

**`payslip_picture`, `national_id_picture`, `signature` and `witness_signature` on `loans` are read and written by
nothing.** V6 copied them into `loan_documents` (version 1 of PAYSLIP / NATIONAL_ID / SIGNATURE / WITNESS_SIGNATURE)
and the same change unmapped them (the unused `Customer` embeddable still names `signature`; it is embedded nowhere).

- A value is either the base64 itself or, as Hibernate stored a `@Lob String`, the OID of a large object holding it
  (digits only; the bytes are in `pg_largeobject`). V5, V6 and V35 all read both forms the same way.
- **V35 cleared a value only where a `loan_documents` row of the same loan and type holds the same bytes**, and
  unlinked its large object (unless another value named it). What it left (no copy, a different copy, a shared or
  unreadable large object, a blank) is counted in its NOTICE and stays. `LegacyLoanDocumentColumnsMigrationIT`.
- **Dropping the columns is a separate owner decision**, not a cleanup. Space comes back only with an operator
  `VACUUM` (`loans`, `pg_largeobject`); `VACUUM FULL` locks.
- **Never run `vacuumlo` on this database while a legacy value still holds an OID**: it only sees oid/lo-typed
  columns, these are TEXT, so it would delete those large objects, including the ones with no copy.

## Outbound HTTP clients are pooled

**Every outbound client draws its connections from ONE Apache httpclient5 pool,
`loans-core/.../config/OutboundHttp`** (`PoolingHttpClientConnectionManager`,
sized by `outbound-http.*`: 50 total / 20 per route by default, plus idle and
expired eviction, a connection time-to-live and validate-after-inactivity). That
is the `RestTemplate` carrying every Ndasenda and InnBucks call (`RestConfig`,
timeouts still from `http.client.*`) and the notification API and WhatsApp
`RestClient`s. The same class lives in InnRewards and market-place; keep them
alike.

- **No per-call clients, and no default factory.** Get a factory from
  `OutboundHttp.requestFactory(connectMs, readMs)` and set it; never `new
  SimpleClientHttpRequestFactory()`, `new RestTemplate()` or a bare
  `RestClient.builder()` without a factory (Spring then picks one from the
  classpath — the JDK `HttpClient` speaks HTTP/2 and broke the fleet's WireMock
  contract tests with `RST_STREAM`).
- **No automatic retries — ever.** `disableAutomaticRetries()`: httpclient5's
  default re-sends an idempotent request after an I/O error and ANY request
  after a 429/503. An InnBucks booking or deposit and a Ndasenda lodgement are
  irreversible and pay out; a second send is a second payment. Redirects are
  followed for GET only, never for a POST. `RestConfigTest` pins both on the
  wire.
- **HTTP/1.1** (the classic httpclient5 transport never negotiates HTTP/2), no
  cookie store, no added `Accept-Encoding`, system proxy/TLS properties
  honoured.
- **Pre-send failures are named for the money paths.** `ConnectPhase` and
  `LodgementException.neverLeft` read httpclient5's `ConnectTimeoutException`
  and `ConnectionRequestTimeoutException` (a timed-out wait for a pooled
  connection, thrown before anything is written) as "never sent";
  `NoHttpResponseException` and every after-connect failure stay possibly-sent.
- A client keeps its own timeouts; the wait for a pooled connection (2s) is
  capped at its connect timeout. A factory's `destroy()` never closes the shared
  client. Pool gauges: `httpcomponents.httpclient.pool.*{httpclient=outbound}`.
- Contract tests build their client on `testsupport/TestOutboundHttp.POOL`.
  Pinned by `OutboundHttpTest`, `OutboundHttpWiringTest` and `RestConfigTest`.
