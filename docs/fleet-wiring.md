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
# Loans' anonymous forgot-password, refused at the edge (404) while loans has no
# working SMS channel in the cell (section 8). BEFORE loans-service-route.
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
    `NDASENDA_*`, `INNBUCKS_NOTIFY_*` and `WHATSAPP_API_KEY` credentials.
  - **Explicit `env:` entries**, named key by key: `JAVA_TOOL_OPTIONS` (heap
    percentage, and `user.home` plus the PDFBox font cache on `/tmp`, because
    the root filesystem is read-only), `SPRING_PROFILES_ACTIVE=api`,
    `SPRING_PROFILES_INCLUDE` and `SPRING_APPLICATION_JSON` pinned to empty
    values (`""` and `{}`),
    `SERVER_PORT=8088`, `DB_URL`, `DB_USERNAME` / `DB_PASSWORD` (from the cell's
    postgres keys), `DB_POOL_MAX` / `DB_POOL_MIN`, and from the cell's ConfigMap
    `INNBUCKS_COUNTRY`, `PUBLIC_API_PREFIX` and `WHATSAPP_GATEWAY_URL` (the
    WhatsApp key stays in loans' own Secret).

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
loans is `@Profile("scheduled-tasks")`: Ndasenda lodgement (irreversible),
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

## 8. Passwords in the cell: SMS reaches nobody

Loans sends SMS through the InnBucks core gateway adapter
(`INNBUCKS_GATEWAY_URL`). The cell has no working value for it, so
`loans.example.env` leaves it out on purpose, and every loans SMS in the cell
fails and is logged. Two flows deliver a password by SMS and by nothing else:

- **Creating a user.** The generated password goes out by SMS only, so a user
  created in the cell never receives it.
- **`POST /lending/v1/auth/forgot-password`**, which is anonymous. It replaces
  the account's password FIRST and only then sends the new one, best-effort
  (`CreateUserServiceImpl.resetPassword`, `NotificationServiceImpl.sendSms`). In
  the cell one request would give any account whose username is known, `admin`
  first, a password nobody receives. That is why the gateway refuses the path at
  the edge (section 1). Remove that route only once loans' SMS reaches people in
  the cell, and then give the path an IP-keyed, fail-safe limiter ahead of
  `loans-service-route`, shaped like the fleet's own `auth-password-reset-route`.
  The catch-all's limiter is keyed on the bearer header, which an anonymous
  caller picks for itself, so it would not slow this path down.

The way in for both is the super-admin reset,
`POST /lending/v1/users/{userId}/password-reset` with `channel` `EMAIL` (needs
the `INNBUCKS_NOTIFY_*` keys in loans' Secret) or `WHATSAPP` (needs
`WHATSAPP_API_KEY` there; the URL comes from the cell). It delivers first and
changes the password only after delivery has succeeded. Provision at least one
of the two before anyone is given a loans account in the cell, and keep a
second SUPER_ADMIN: `admin` is created only while it is absent, so the bootstrap
cannot restore a lost `admin` password.

## 9. Supply chain: below the fleet's bar, called out

The cell now runs `ghcr.io/mpofuslim/loans-api`, and `release.yml` does not yet
meet the fleet's supply-chain invariants (ticketing `CLAUDE.md`, "CI/CD &
supply-chain integrity"):

- it pushes the image BEFORE Trivy runs, and Trivy is report-only
  (`--exit-code 0`);
- Trivy is installed with the installer from the mutable `main` branch, not a
  pinned version;
- its actions are on movable tags, not commit SHAs;
- no test job gates the build (the jar is built with `-DskipTests`);
- the runtime image is JRE 25 (`eclipse-temurin:25-jre-alpine`), while `ci.yml`
  tests on 21.

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
4. Run the image on the JRE that `ci.yml`'s `java-version` tests on.

The box's own workflow is kept off the cell's tags meanwhile (section 7).
