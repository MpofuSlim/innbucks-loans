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
