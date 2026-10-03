# Fleet wiring: loans-service behind the ticketing-system api-gateway

The loans API joins the InnBucks fleet the same way marketplace-service
(`MpofuSlim/market-place`) does. Browsers and apps reach it through the fleet
api-gateway, and it is listed in the gateway's aggregated Swagger UI. It runs as
a Kubernetes Deployment and Service named **`loans-service`** in namespace
`ticketing`. The service name, the port (**8088**) and the image
(`ghcr.io/mpofuslim/loans-api`) are the contract between the two repos. Change
one only in lock-step with `MpofuSlim/ticketing-system`.

This file records what each repo carries. Nothing on the ticketing side is needed
to build or test this repo. It is needed to run loans in the cell.

**Loans keeps its own sign-in, by design.** Lending has its own portal and its
own identity (owner's decision, 2026-09-30): its own users table,
`POST /lending/v1/auth/login`, its own HS256 key and `iss: innbucks-loans`. It
is not a user-service fleet-token consumer and must never hold the fleet's
`JWT_SECRET` (see section 5). The gateway needs nothing for this: it never
validates a bearer token, it only uses the token as a rate-limit key. A loans
token therefore passes through untouched, and loans remains the authority on it.

## 1. Gateway routes: `api-gateway/src/main/resources/application.yaml`

```yaml
# Loans' anonymous forgot-password, refused at the edge (404) until the path has
# its own IP-keyed limiter (section 8). BEFORE loans-service-route.
- id: loans-forgot-password-deny
  uri: forward:/__edge_deny__
  predicates:
    - Path=/lending/*/auth/forgot-password

# Every loans controller maps under /lending/v1 (ApiPaths.BASE). The predicate
# is /lending/** so a /lending/v2 shipped beside it needs no gateway change.
- id: loans-service-route
  uri: lb://loans-service
  predicates:
    - Path=/lending/**
  filters:
    - name: RequestRateLimiter    # same limiter and key-resolver as every authenticated route
      ...

# The spec for the aggregated Swagger UI.
- id: loans-service-proxy-route
  uri: lb://loans-service
  predicates:
    - Path=/loans-service/v3/api-docs,/loans-service/v3/api-docs/**
  filters:
    - StripPrefix=1
    - name: RequestRateLimiter
      ...
```

In `springdoc.swagger-ui.urls`, the "Select a definition" dropdown:

```yaml
- name: loans-service
  url: ${PUBLIC_API_PREFIX:}/loans-service/v3/api-docs
```

- **No internal deny route.** Loans has no internal S2S surface. If one is
  added later, the three-files-must-agree rule applies: the controller's token
  check, this repo's `ApiSecurityConfig` permit, and a gateway
  `loans-internal-deny` route placed BEFORE `loans-service-route`.
- **The proxy segment must end in `-service`.** The gateway's Swagger Basic-auth
  gate (and its 404 under prod) matches `/*-service/v3/api-docs`. A docs prefix
  with any other ending would publish the loans spec without a password.
- `GatewayRouteTableTest` pins the three route ids, and that the deny route
  matches before `loans-service-route`.

What this asks of loans: the spec stays at the root `/v3/api-docs` (springdoc
`api-docs.path`), which `ApiSecurityConfig` already permits without a token.

## 2. The Swagger entry: "/foundry - Gateway relative server"

The gateway UI shows each spec's `servers`. For Try-it-out to work through the
edge, the one server must be the gateway, relative to the page. Every fleet
service does it the same way: `url = PUBLIC_API_PREFIX` (blank means `/`) and
description `Gateway relative server`. The cell sets `PUBLIC_API_PREFIX=/foundry`,
so Try-it-out calls `/foundry/lending/v1/...`. nginx strips `/foundry`, and the
gateway routes `/lending/**` to loans.

Loans side: `config/OpenApiConfig` publishes that server. `@OpenAPIDefinition` on
`LoansApiApplication` keeps the title, the description and the `Bearer Token`
scheme, and names **no servers**. springdoc applies that annotation after the
`OpenAPI` bean, so any servers named there would replace the gateway entry. The
old Sandbox, Local and QA entries were removed for that reason. `OpenApiConfigTest`
pins both halves.

## 3. Discovery map: every service's `application.yaml` (ticketing)

One line in both documents of the static map, in the gateway and in all five
other ticketing services. The map must be identical everywhere, and each port
must equal the k8s Service port. `FleetServiceMapTest` fails the build otherwise.

```yaml
loans-service:       [{ uri: "http://loans-service:8088" }]   # default document
loans-service:       [{ uri: "http://localhost:8088" }]       # local profile
```

**Loans carries no copy of the map.** It has no Spring Cloud dependency and
calls no fleet sibling. Its outbound calls go to InnBucks, Ndasenda, the
notification API and the WhatsApp gateway, which are external providers with
explicit URLs, like the fleet's own provider clients. Add the map here only if
loans ever calls a sibling by name.

## 4. Kubernetes: `deploy/k8s/` (ticketing)

- **The Service goes top level, in `04-services.yaml`:**
  `loans-service`, port 8088 to targetPort 8088. `FleetServiceMapTest` reads only
  the top-level directory, and each map port must match a Service there.
- **The Deployment is opt-in, in `deploy/k8s/loans/loans-service.yaml`.**
  `deploy/k8s` is applied to BOTH ZW hosts, and loans runs on the staging box
  only until a separate go-live decision. The routine non-recursive
  `kubectl apply -f deploy/k8s/` never creates it; staging applies
  `deploy/k8s/loans/` deliberately. On a host without the Deployment, the Service
  has no endpoints, and a `/lending/**` call ends in a gateway error instead of
  reaching anything.
- Fleet hardening, as every Deployment: non-root UID 10001, read-only root
  filesystem, `/tmp` emptyDir, all capabilities dropped, no service-account
  token. `replicas: 1` with `strategy: Recreate` (see section 6).
- Probes on the app port: readiness `GET /actuator/health`, liveness
  `GET /actuator/health/liveness`.

What this asks of loans, all in this repo:

| Need in the cell | Loans side |
|---|---|
| Port 8088 | `server.port: ${SERVER_PORT:8088}`, `EXPOSE 8088` |
| Fleet database variables | `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `DB_POOL_MAX`, `DB_POOL_MIN` (defaults: the local dev database, pool 19/7) |
| Probes | `spring-boot-starter-actuator`. Health is the ONLY endpoint exposed, it shows no details, and `ApiSecurityConfig` permits exactly `/actuator/health` and `/actuator/health/**` |
| Read-only root filesystem | Console logging only. The old rolling file appender under `logs/` aborted the boot there |
| No mounted config | The image loads `/app/config` as `SPRING_CONFIG_ADDITIONAL_LOCATION`, so the packaged `application.yml` always loads and a mounted file (the box's) overrides it key by key. `SPRING_CONFIG_LOCATION` replaced the packaged file, so a pod with nothing mounted ran without a port, a datasource or partner URLs |
| CORS owned by the gateway | Loans declares no CORS. The gateway adds a backend's response headers to its own, so two `Access-Control-Allow-Origin` values would reach the browser, which refuses them. `GatewaySurfaceTest` pins it |
| Stable signing evidence | `server.forward-headers-strategy: none`. Left unset, Boot enables Tomcat's forwarded-header handling by itself when it detects Kubernetes, and the address and `X-Forwarded-For` chain recorded on a signed application would change meaning between the box and the cell |

## 5. Database and environment

- **Database `loans_service`** on the cell postgres, new and empty. The staging
  loans data is seed data only and is not migrated. It is created once, by the
  procedure in ticketing's `deploy/k8s/README.md`, section 6, step 1 (a no-op
  when the database already exists, followed by a refresh of `pg-init` so a
  rebuilt volume has it too). Flyway builds V1 onward at first boot. Never point
  the datasource at a database that already holds another schema:
  `JpaSchemaConfig` turns on `baselineOnMigrate`, so such a database would be
  baselined at V1 and the loans migrations would run against it.
- **Credentials:** the cell's `POSTGRES_USER` / `POSTGRES_PASSWORD`, which is the
  fleet convention.
- **The Deployment does NOT `envFrom` `cell-zw` or `cell-zw-secrets`.** Spring
  binds environment variables onto properties by name (relaxed binding), ahead
  of any yml or `${...}` placeholder. The cell carries names that loans reads
  for other purposes:
  - `JWT_SECRET` would become loans' `jwt.secret`, and loans would sign with
    the fleet key. A holder of either system's key could then mint tokens for
    both. Loans reads roles straight from its own claims and books loans with
    InnBucks, which pays on booking.
  - `SPRING_PROFILES_ACTIVE=prod,json` would replace `api`.
  - `BOOTSTRAP_ADMIN_PASSWORD` would seed the loans super-admin.
  - `INNBUCKS_GATEWAY_URL`, `WHATSAPP_*` and every other fleet secret would sit
    in this process for nothing, or send loans' messages where the fleet's go.

  The pod therefore gets two sources only:
  - **`envFrom: secretRef loans-service-secrets`** holds loans-only keys. It is
    created on the host from the gitignored `deploy/cells/loans.zw.local.env`
    (template: `deploy/cells/loans.example.env`). Required: `JWT_SECRET` (loans'
    OWN key, at least 32 bytes, `openssl rand -base64 48`, never the fleet's)
    and `BOOTSTRAP_ADMIN_PASSWORD` (the first boot on an empty `loans_service`
    creates `admin`; clear it afterwards). Optional: the `INNBUCKS_*`,
    `NDASENDA_*`, `INNBUCKS_NOTIFY_*` and `WHATSAPP_API_KEY` credentials, the
    two voucher code keys (section 10), and `LOANS_PORTAL_SIGN_IN_URL` (where
    portal users sign in: the link in the message that gives them a temporary
    password; not a secret, but loans' own, so it lives here too).
  - **Explicit `env:` entries**, named key by key: `JAVA_TOOL_OPTIONS` (heap
    percentage, and `user.home` plus the PDFBox font cache on `/tmp`, because
    the root filesystem is read-only), `SPRING_PROFILES_ACTIVE=api`,
    `SPRING_PROFILES_INCLUDE` and `SPRING_APPLICATION_JSON` pinned to empty
    values (`""` and `{}`),
    `SERVER_PORT=8088`, `DB_URL`, `DB_USERNAME` / `DB_PASSWORD` (from the cell's
    postgres keys), `DB_POOL_MAX` / `DB_POOL_MIN`, and from the cell's ConfigMap
    `INNBUCKS_COUNTRY`, `PUBLIC_API_PREFIX` and `WHATSAPP_GATEWAY_URL` (the
    WhatsApp key stays in loans' own Secret), and the cell's SES settings for
    email, `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_FROM` (ConfigMap) and
    `MAIL_USERNAME` / `MAIL_PASSWORD` (Secret), each optional (section 8).
    Never `MAIL_SENDER_NAME`: the cell's is `Foundry`, loans' is its own.

- **The Secret holds only the keys `loans.example.env` lists.** An explicit
  `env:` entry wins over `envFrom` for the SAME key only. Spring reaches one
  property through several names, so a different name in the Secret still
  overrides what the Deployment sets:
  - `SPRING_DATASOURCE_URL` names `spring.datasource.url` directly and beats the
    `${DB_URL}` placeholder in `application.yml`. With the cell postgres
    superuser in this pod and `baselineOnMigrate` on, that could run the loans
    migrations into another service's database.
  - `SPRING_PROFILES_GROUP_API=scheduled-tasks` (or `SPRING_PROFILES_INCLUDE`,
    or `SPRING_APPLICATION_JSON`, if the pins above were ever dropped) adds the
    scheduled jobs beside `api`.
  - `MANAGEMENT_*` would expose more of the actuator than health.

  So never a `SPRING_*`, `SERVER_*`, `DB_*`, `JAVA_*` or `MANAGEMENT_*` key in
  it. The pins cover the likeliest spellings; no list of pins covers every name.
  For the money-moving jobs there is also a guard in code (section 6).

## 6. Scheduled jobs: OFF in the cell

`SPRING_PROFILES_ACTIVE` is `api` and nothing else. Every `@Scheduled` job in
loans but the Staff Grocery Loan ones (below) is `@Profile("scheduled-tasks")`: Ndasenda lodgement (irreversible),
InnBucks booking (which PAYS on the apply call), disbursement status, the saga,
the Ndasenda batch commit and responses, draft expiry and workflow escalation.
Turning the profile on acts on whatever backlog the database holds at the first
tick. It is its own go-live step, reviewed on its own. Never add
`scheduled-tasks` to a manifest in passing.

**In a Kubernetes pod the profile alone stops the boot.**
`ScheduledTasksKubernetesGuard` refuses to start with `scheduled-tasks` active
unless `loans.scheduled-tasks.allowed-on-kubernetes`
(`SCHEDULED_TASKS_ALLOWED_ON_KUBERNETES`) is `true`. The Deployment's
`SPRING_PROFILES_ACTIVE` cannot keep the jobs off by itself, because a Secret
entry under another name (section 5) can add the profile. With the guard, a
profile that arrived that way crash-loops the pod before any job has run. The
go-live step sets both the profile and the opt-in, as explicit `env:` entries in
the Deployment where a review sees them, never in the Secret. Outside Kubernetes
(the staging box, a developer's machine) nothing changes.
`ScheduledTasksKubernetesGuardTest` pins it.

**The Staff Grocery Loan jobs have their own switch.** The weekly offer run
(FR-SGL-015) and its messages, the retry of staff messages left pending,
voucher upkeep and the daily arrears email (FR-SGL-045) move no money, so they run where `STAFF_LOANS_JOBS_ENABLED` is
`true` without `scheduled-tasks` (`@StaffLoanJobs`). It is one key in loans' own
Secret: no manifest change and no `kubectl apply`, just the Secret and a
restart. Turning it on starts the Monday run (`STAFF_OFFERS_RUN_CRON`), which
messages every eligible staff member. `StaffLoanJobsWiringTest` fails if a job
that pays, books or lodges is ever put behind this switch.

The jobs are only safe run serially in ONE process (`application.yml`,
`spring.task.scheduling`; there is no ShedLock). That is why the Deployment
runs one replica with `strategy: Recreate`, so no two pods ever overlap, not
even during a rollout.

## 7. The staging box

Until the cutover the box runs the loans container by hand
(`docker run ... -p 8080:8080 -v ~/apps/loans-api/config:/app/config`); the SSH
deploy and rollback workflows were removed. The image loads its packaged
`application.yml` (port 8088) with the box's `/app/config` file on top, key by
key. The box file sets `server.port: 8080`, so the box keeps publishing 8080;
add `-e SERVER_PORT=8080` to the `docker run` if that file ever loses the key.

**Image tags.** `release.yml` pushes `ghcr.io/mpofuslim/loans-api:latest` and
`:sha-<full commit SHA>` on every merge to `main`, like the rest of the fleet.
The cell's Deployment names `:latest` and is pinned to `sha-<commit>` when
deployed (ticketing's `deploy/k8s/README.md` section 6).

**CORS:** this image sends no CORS headers. A browser page that calls the box
container directly, cross-origin, stops working once the box runs it. Move such
callers to the gateway (`https://<edge>/foundry/lending/v1`) first, and add their
origin to the gateway's `CORS_ALLOWED_ORIGINS`. A staging-only origin belongs in
the host's `cell.zw.local.env`, because `cell.zw.env` is shared with production.

## 8. SMS in the cell, and passwords

Loans sends SMS through the InnBucks notification API
(`POST /api/notification/sms`), the same API, credentials and wire body as the
ticketing fleet's SMS client. The credentials
are loans' own `INNBUCKS_NOTIFY_URL` / `_API_KEY` / `_USERNAME` / `_PASSWORD`
in its Secret, set to the values the fleet keeps as `BANK_API_*` (loans never
reads the cell's names, section 5). Without them every loans SMS and email fails
and is logged. Staff Grocery Loan messages (offers, the launch, vouchers) and
portal users' temporary passwords go by WhatsApp first and fall back to SMS, so
they need `WHATSAPP_API_KEY` in the Secret too (the URL comes from the cell);
without it they all go by SMS.

**Email goes the way the ticketing fleet sends Foundry's.** Every loans email
(a temporary password, an escalation, a credit-authority notice, a Staff Grocery
Loan employment flag) is the InnBucks branded HTML, signed "The InnBucks Lending
Team" (`INNBUCKS_NOTIFY_HTML_ENABLED=false` sends plain text with the same
footer; `INNBUCKS_NOTIFY_LOGO_URL` is an optional hosted logo). It goes:

1. over SMTP (Amazon SES) when the cell has it on (`MAIL_ENABLED=true` with
   `MAIL_HOST`, `MAIL_FROM` and the SES SMTP credentials), from
   `InnBucks Lending <MAIL_FROM>` (`LOANS_MAIL_SENDER_NAME` changes the name), so
   the inbox shows loans' own name rather than the notification API's;
2. otherwise, or when the SMTP send fails, through the notification API, as
   before.

A temporary-password email also carries a "Sign in to InnBucks Lending" button
when `LOANS_PORTAL_SIGN_IN_URL` is an `https` address, and nothing an upstream
sends back about it is logged (a refusal can quote the password). The mail
health check is off: an unreachable SMTP host never takes loans out of service.

It used to post to the InnBucks core gateway adapter (`INNBUCKS_GATEWAY_URL`), a
retired host, so no loans SMS reached anyone in the cell. Two flows deliver a
password by SMS and by nothing else:

- **Creating a user.** The generated password goes out by SMS only. With the
  `INNBUCKS_NOTIFY_*` keys provisioned it now arrives; without them the user
  never receives it.
- **`POST /lending/v1/auth/forgot-password`**, which is anonymous. It replaces
  the account's password FIRST and only then sends the new one, best-effort
  (`CreateUserServiceImpl.resetPassword`, `NotificationServiceImpl.sendSms`).
  The gateway refuses the path at the edge (section 1). Working SMS is the first
  of two conditions for removing that route: the new password then reaches the
  account's owner rather than nobody, but one request still changes any known
  account's password, `admin` first. The second is an IP-keyed, fail-safe
  limiter ahead of `loans-service-route`, shaped like the fleet's own
  `auth-password-reset-route`. The catch-all's limiter is keyed on the bearer
  header, which an anonymous caller picks for itself, so it would not slow this
  path down. Lift the deny only with both in place.

The super-admin reset, `POST /lending/v1/users/{userId}/password-reset` with
`channel` `EMAIL` (needs the `INNBUCKS_NOTIFY_*` keys) or `WHATSAPP` (needs
`WHATSAPP_API_KEY`), delivers first and changes the password only after delivery
has succeeded. Provision at least one of the two before anyone is given a loans
account in the cell, and keep a second SUPER_ADMIN: `admin` is created only
while it is absent, so the bootstrap cannot restore a lost `admin` password.

## 9. Supply chain: below the fleet's bar, called out

The cell now runs `ghcr.io/mpofuslim/loans-api`, and `release.yml` does not yet
meet the fleet's supply-chain invariants (ticketing `CLAUDE.md`, "CI/CD &
supply-chain integrity"):

- it pushes the image BEFORE Trivy runs, and Trivy is report-only
  (`--exit-code 0`);
- Trivy is installed with the installer from the mutable `main` branch, not a
  pinned version;
- its actions are on movable tags, not commit SHAs;
- no test job gates the build (the jar is built with `-DskipTests`).

The JDK is no longer one of the gaps: `ci.yml` and `release.yml` build and test
on 25, the JRE the image runs (`eclipse-temurin:25-jre-alpine`), with bytecode
still targeting 21 (`<release>`), and Lombok is an explicit processor path in
both modules (JDK 23+ no longer runs one found on the classpath). Dependabot
ignores the base image's major bumps, so the JDK moves only in one reviewed
change, to an LTS, with `java-version` beside it.

This is a deliberate gap for this step, closed by its own change. Turning the
scan into a gate before the image's current findings are triaged into
`.trivyignore` could fail every Release and hold back the very image this step
needs. Until that change lands, a green Release means the image BUILT, not that
it is clean: read the run's "Scan image with Trivy" log for CRITICAL and HIGH
findings before pinning a commit in the cell.

The follow-up, in order:

1. A test job that the build job `needs`.
2. Build with `load: true, push: false`. Scan with `--exit-code 1` against a
   governed `.trivyignore`, where each entry names an owner, a reason and a
   review date. Push only after that.
3. Pin every action to a commit SHA (Dependabot keeps them current), and pin the
   Trivy version.

The box's own workflow is kept off the cell's tags meanwhile (section 7).

## 10. Vouchers: two keys, the merchant, and its till accounts

Staff Grocery Loan vouchers (FR-SGL-033 to 040) need two keys in loans' Secret,
both generated with `openssl rand -base64 32`, both different:

- `VOUCHER_CODE_HMAC_KEY` finds a voucher by the code a till sends, without the
  code being stored. **Never change it while vouchers are open**: every code
  issued under the old key stops being found.
- `VOUCHER_CODE_ENCRYPTION_KEY` (base64 of exactly 32 bytes) seals the code so it
  can be sent again and shown to a VOUCHER_SUPPORT user.

With neither key, vouchers are off: every voucher endpoint answers 503
`VOUCHERS_UNAVAILABLE`, a boot WARN says so, and nothing else is affected. With
one but not the other, or a malformed one, loans refuses to start. Both are
secrets, like the JWT key: a holder of both and a database copy holds every
open voucher's value.

The rules are plain env keys too, with defaults (`application.yml`,
`loans.vouchers`): `VOUCHER_CODE_LENGTH` (16), `VOUCHER_VALIDITY_DAYS` (30, the
voucher lapses at the end of that market day), `VOUCHER_PARTIAL_REDEMPTION_ALLOWED`
(true) and `VOUCHER_DELIVERY_CHANNELS` (`WHATSAPP,SMS`, tried in that order).
Validity and partial redemption are OQ-08, still to be agreed with GetMore.

**The merchant is a record, not a setting.** The Staff Grocery Loan is for one
of loans' merchants (`GET /lending/v1/staff-loan-merchant`); a super-admin
changes which with `PUT` (audited `STAFF_LOAN_MERCHANT_CHANGED`). It must be paid
to its own account (`MERCHANT_MOBILE_WALLET`). A loan keeps the merchant it was
accepted for, and so does its voucher. V29 created GetMore Groceries
(`getmore-groceries`) as that merchant, with no account yet: set its settlement
account with `PUT /lending/v1/merchants/getmore-groceries` once it is known.
`STAFF_LOANS_MERCHANT_NAME` is gone.

**A merchant's till integration signs in as a loans user in group
`MERCHANT_TILL`** (formerly `GETMORE`), created by a super-admin under that
merchant (`POST /lending/v1/merchants/{merchantCode}/users`), and reaches
`POST /lending/v1/voucher-validations` and `/voucher-redemptions` through the
existing `/lending/**` route (no gateway change). It takes only its own
merchant's vouchers: another merchant's is answered as an unknown code. A
`MERCHANT_TILL` token is refused everywhere else, whatever other group it holds
(`VoucherRoleFilter`), so the credential a merchant holds can never read a loan
or submit one. V29 moved the existing `GETMORE` accounts to GetMore Groceries
under the new name and ended their sessions: they sign in again. Staff who may
see a code in full are in group `VOUCHER_SUPPORT`; on its own that group reaches
the voucher screens and nothing more.

Vouchers are issued by the disbursement (FR-SGL-032, BR.NET), never from a
screen, so until that lands none exist in the cell.

## 11. SuperApp borrowers: the middleware's assertion, and staging's stand-in

A Staff Grocery Loan borrower signs in from the SuperApp (FR-SGL-025), not
through loans' own login. The SuperApp user has already signed in at the
InnBucks middleware with their PIN or biometrics; the middleware signs a
short-lived assertion that they did, and the app trades it at
`POST /lending/v1/auth/exchange` for a 15-minute **borrower session**. Loans never
sees the PIN, and holds only the middleware's PUBLIC key, so nothing in loans
can mint an assertion.

**The assertion contract** is the one the fleet's `/auth/exchange` already
verifies (`FederationAssertionVerifier` in ticketing's user-service), with
loans' own audience, so the middleware signs one shape for everyone:

| Claim | Value |
|---|---|
| `alg` | RS256/384/512 or ES256/384/512. Never HS*, never `none`. |
| `iss` | `innbucks-middleware` (`BORROWER_ASSERTION_ISSUER`) |
| `aud` | `innbucks-lending` (`BORROWER_ASSERTION_AUDIENCE`). Differs from the fleet's `innbucks-foundry` on purpose: a fleet login is not a loan login. |
| `sub` | the phone, any Zimbabwean spelling (`+263773456789`, `0773456789`) |
| `jti` | unique, at most 128 characters; each is accepted once, ever |
| `iat`, `exp` | both required; `exp - iat` at most 300 s; 30 s of clock skew |
| `amr` | how they authenticated: `pin`, `fpt` (fingerprint), `face` |

Who the borrower is comes from the **staff register**, by phone, never from
anything else in the assertion. A phone not on the register, or a staff member
who has left, is `403 NOT_ON_STAFF_REGISTER`. Every other failure (forged,
expired, wrong audience, used before) is one opaque `401 ASSERTION_REJECTED`,
with the reason in the log and the audit trail (`BORROWER_SIGN_IN_REFUSED`).
Used assertions are recorded in `borrower_assertion_uses` (loans has no Redis).
Approving a loan will take a FRESH assertion (signed in the last 120 s, `amr` of
`pin`, `fpt` or `face`: FR-SGL-028), spent the same way.

**A borrower session is not a staff session.** It carries the `BORROWER` role and
nothing else, names a staff member rather than a user, reaches
`/lending/v1/borrower/**` and nothing else, and those endpoints refuse every
staff session, SUPER_ADMIN's included (`BorrowerSessionFilter`). It dies the
moment the staff member leaves, is removed from the register or changes number
(`TokenVersionValidator`), and is not refreshed: the app signs in again with a
fresh assertion.

Env keys (loans' Secret, `loans.<iso>.local.env`):

- `BORROWER_ASSERTION_PUBLIC_KEY`: the middleware's public key, PEM or bare
  base64 of the X.509 encoding. An env file holds one line per key, so write the
  PEM's line breaks as `\n`. **Blank, nobody can sign in**: `/auth/exchange`
  answers `503 BORROWER_SIGN_IN_UNAVAILABLE` and the boot log WARNs. Nothing else
  is affected.
- `BORROWER_ASSERTION_PREVIOUS_PUBLIC_KEY`: the key before a rotation, accepted
  beside the new one until every assertion signed with it has expired (5 minutes).
- `BORROWER_SESSION_MINUTES` (15), `BORROWER_ASSERTION_MAX_TTL_SECONDS` (300),
  `BORROWER_STEP_UP_MAX_AGE_SECONDS` (120), `BORROWER_STEP_UP_METHODS`
  (`pin,fpt,face`): defaults that need no entry.

**Staging only, until the middleware signs: test assertions.** With
`BORROWER_TEST_ASSERTIONS_ENABLED=true`, loans signs assertions itself, in exactly
the middleware's shape, for ANY phone, to whoever presents
`BORROWER_TEST_ASSERTIONS_API_KEY` (at least 32 characters) in `X-Api-Key` at
`POST /lending/v1/auth/test-assertions`; `BORROWER_TEST_ASSERTIONS_PRIVATE_KEY` is
the RSA key it signs with (PKCS#8, one line). The SuperApp then runs the real
flow, sign in and approve, and switches to the middleware's assertions with no
change but where it gets them. **Whoever holds that api key can sign in and
borrow as any staff member**: it is announced at ERROR on every boot, the
endpoint does not exist unless it is on (a plain 404, whatever is sent), and it
must never be on where real money moves. Switched on without both keys, loans
refuses to start.

No gateway change: both `/auth/exchange` and `/auth/test-assertions` ride the
existing `/lending/**` route like loans' own login.

**What still needs the middleware team:** sign the assertion at login (and on a
PIN or biometric prompt at loan approval) with `aud: innbucks-lending`, and hand
over the public key. Until then staging runs on test assertions and production
has no borrower sign-in, which is the documented state, not a fault.

## 12. The Staff Grocery Loan journey: settings, and the agreement to publish

With a borrower signed in (§11), the SuperApp takes the loan through
`/lending/v1/borrower/staff-grocery-loan`:

1. the tile (`GET`);
2. "Apply" without an offer (`POST /offers`);
3. the disclosure and agreement for an amount (`POST /quote`);
4. acceptance with a fresh PIN or biometric assertion (`POST /loans`).

An accepted loan is `AWAITING_DISBURSEMENT` until the bank's system (BR.NET)
pays the merchant and issues the voucher. That integration is not wired yet, so no
loan goes further. Credit, Finance and Human Capital see accepted loans at
`/lending/v1/staff-loans`; Credit can cancel one before payout.

**Nothing can be accepted until the agreement is published.** A SUPER_ADMIN
publishes it as an instrument, Legal's wording, using the placeholders listed by
`GET /lending/v1/instrument-templates/placeholders?instrumentType=STAFF_GROCERY_LOAN_AGREEMENT`:

```
POST /lending/v1/instrument-templates
{ "instrumentType": "STAFF_GROCERY_LOAN_AGREEMENT", "title": "...", "body": "..." }
```

Until then a quote or an acceptance answers `503 STAFF_LOAN_TERMS_UNAVAILABLE`
and logs an ERROR. Publishing a new version makes any quote shown under the old
one answer `409 TERMS_CHANGED` at acceptance, so the borrower reads the new
wording first.

Three open business questions are settings, each defaulting to its working
answer. They are plain env keys in loans' Secret, needed only to change the
default:

| Key | Default | Decides |
|---|---|---|
| `STAFF_LOANS_MINIMUM_DRAW` | `10.00` | Least a borrower may take (OQ-03). |
| `STAFF_LOANS_DRAW_INCREMENT` | `5.00` | Steps below the full offer (OQ-03). The minimum must be a multiple of it. |
| `STAFF_LOANS_REPAYMENT_DAY` | `20` | Day of the following month it is due: the salary day (OQ-02, BRD 3.7). At most 28. |
| `STAFF_LOANS_ARREARS_GRACE_DAYS` | `0` | Days past due before a disbursed loan counts as arrears. |
| `STAFF_LOANS_ARREARS_ESCALATION_DAYS` | `30` | Days past due before an unpaid loan is escalated to Credit in the daily arrears report (BRD 3.8). |
| `STAFF_LOANS_ARREARS_REPORT_CRON` | `0 0 7 * * *` | When the daily arrears report is emailed to Credit and Human Capital, on the market's clock (FR-SGL-045). Only where the Staff Grocery Loan jobs run. |
| `STAFF_LOANS_UNREDEEMED_VOUCHER_TREATMENT` | `DEBT_STANDS` | Or `REDUCED_TO_AMOUNT_SPENT` (OQ-09). Shown to the borrower and kept on each loan. |
| `STAFF_LOANS_CURRENCY` | `USD` | |
| `STAFF_LOANS_PAYROLL_EMAILS` | blank | Payroll's mailboxes, comma-separated: emailed with Human Capital when a borrower leaves owing a paid-out loan. Blank: Human Capital alone, and the boot log warns. |

When Human Capital moves a borrower off ACTIVE, a loan still awaiting payout is
cancelled. A paid-out loan (DISBURSED or WRITTEN_OFF) is flagged instead
(`employmentFlag` on `GET /lending/v1/staff-loans`, `?flagged=true` lists them)
and, once the register approval commits, the people who must act are emailed:
Human Capital and the Payroll mailboxes for RESIGNED or TERMINATED (recover it
from terminal benefits), CREDIT_MANAGER users for SUSPENDED or UNPAID_LEAVE
(Credit decides the due date). The flag moves with each later change and clears
when they are ACTIVE again, and whoever was told about it hears that too. The
emails go as every loans email does (section 8: SES when the cell has it, else
the notification API) to the email addresses on the portal users, so each Human
Capital and Credit user needs one.

Arrears and active loans come from loans' own records for now. Other InnBucks
facilities, and the balance once disbursed, are the core banking system's to
report when that integration lands.
