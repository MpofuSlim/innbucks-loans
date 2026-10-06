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
