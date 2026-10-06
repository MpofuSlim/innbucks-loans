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
