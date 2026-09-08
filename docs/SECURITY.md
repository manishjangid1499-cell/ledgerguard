# LedgerGuard Security Architecture & Policy

## 1. Security Architecture Principles

- **Authoritative Backend Security**: The backend API enforces all authentication and authorization rules. The frontend UI is treated as untrusted and client-side access controls are for user experience only.
- **No Distributed Auth Complexity**: Authentication and authorization are embedded directly within `ledgerguard-api`. No external authentication microservice or Keycloak cluster is introduced.
- **Defense in Depth**: Every request is authenticated, authorized against ownership boundaries, validated for payload structure, and rate-limited.
- **Stateless Session Policy**: The API operates strictly with `SessionCreationPolicy.STATELESS`. No server-side HTTP session state is retained.
- **Anti-Enumeration Safeguards**: Registration rejects duplicate emails with a structured problem detail, while login endpoints return identical, generic `INVALID_CREDENTIALS` error details for non-existent users, incorrect passwords, or disabled accounts.

---

## 2. Role-Based Access Control (RBAC)

LedgerGuard defines three principal roles:

| Role | Target Identity | Permissions & Scope |
| :--- | :--- | :--- |
| **`ROLE_CUSTOMER`** | End users / Wallet owners | Manage personal profile; view own wallet; initiate peer-to-peer transfers; initiate deposits/withdrawals; view own journal entries and transaction history. |
| **`ROLE_MERCHANT`** | Commercial accounts | Manage merchant profile; accept payments; initiate refunds on owned payments; inspect merchant settlement ledger. |
| **`ROLE_OPS`** | Platform administrators & Operators | Access system-wide integrity metrics; execute reconciliation jobs; inspect outbox queues; trigger Money Integrity Failure Lab scenarios; view system audit logs. |

### Endpoint Authorization Rules
- `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`: Publicly accessible endpoints.
- `/actuator/health/**`, `/actuator/info`: Publicly accessible standard application liveness/readiness health probes.
- `/api/ops/**`, `/api/reconciliation/**`: Strictly restricted to authenticated principals holding `ROLE_OPS`.
- `/api/**`: Requires valid Bearer token authentication by default.

---

## 3. Authentication & Token Management

### Password Hashing
- User passwords are encrypted using **BCrypt** with an adaptive work factor (default `strength = 10` in Spring Security `BCryptPasswordEncoder`). Plaintext passwords never touch logs, storage, or external systems.

### Access & Refresh Token Design
- **Access Tokens**: Short-lived JSON Web Tokens (JWT) signed with HMAC-SHA256 (`HS256`) using a 256-bit+ secret key configured via `LEDGERGUARD_JWT_SECRET`.
  - Claims: `iss = ledgerguard`, `sub = <User UUID>`, `role = <CUSTOMER|MERCHANT|OPS>`, `jti = <Token UUID>`, `iat`, `exp` (default TTL: 15 minutes / 900 seconds).
  - Access tokens never contain password hashes, raw refresh tokens, or PII.
- **Refresh Tokens**: Long-lived, cryptographically random strings (256-bit entropy generated via `SecureRandom` encoded with Base64 URL-safe format).
  - Storage: Stored only as SHA-256 hashes (`token_hash`) in PostgreSQL `refresh_tokens` table. Raw refresh tokens are never persisted.
  - TTL: Default 7 days (604,800 seconds).
  - Revocation & Rotation: Refresh tokens are single-use. Upon rotation (`POST /api/auth/refresh`), the active token is atomically marked revoked (`revoked_at = NOW()`) and a fresh token is issued.
  - Concurrency & Double-Spend Protection: Refresh token rotation uses PostgreSQL row-level pessimistic locking (`SELECT ... FOR UPDATE` via `@Lock(LockModeType.PESSIMISTIC_WRITE)`). If two concurrent requests use the same refresh token, exactly one succeeds and the other fails safely with `401 Unauthorized`.
  - Disabled User Enforcement & Architectural Limitation: Disabled accounts (`status = DISABLED`) are strictly prohibited from logging in (`POST /api/auth/login` -> 401) and cannot rotate or refresh tokens (`POST /api/auth/refresh` -> 401). Because LedgerGuard uses short-lived stateless access JWTs (TTL: 15 minutes) and avoids distributed token-blacklisting infrastructure (e.g. Redis), an access token issued *before* an account is disabled remains valid until its natural expiration. After expiry, the disabled user cannot refresh or obtain new tokens.

### Cookie & Storage Strategy
- Refresh tokens are transmitted via a dedicated HTTP cookie:
  - Cookie Name: `ledgerguard_refresh_token`
  - `HttpOnly = true` (inaccessible to JavaScript)
  - `SameSite = Strict` (mitigates CSRF)
  - `Path = /api/auth` (scoped strictly to authentication endpoints)
  - `Secure = true` in production (`false` configurable in local development via `ledgerguard.security.cookie.secure`).
- Raw refresh tokens are never returned in JSON response bodies.

### Frontend Security & Access Token Lifecycle (Implemented in Phase 5)
- **In-Memory Access Tokens Only**: The frontend application maintains access tokens strictly within JavaScript application memory (via closure/in-memory store). Access tokens are never written to `localStorage`, `sessionStorage`, `IndexedDB`, or JavaScript cookies, mitigating persistent cross-site token theft.
- **Silent Session Restoration**: On initial application load or hard reload, the frontend calls `POST /api/auth/refresh` with `credentials: 'include'`. If a valid `ledgerguard_refresh_token` HttpOnly cookie is present, a fresh access token is loaded into memory without user interaction.
- **Single-Flight Refresh on 401**: When a protected API request encounters an expired access token (`401 Unauthorized`), the native fetch client initiates a deduplicated single-flight token refresh, updates memory, and retries the original request once. Non-protected auth endpoints (`/login`, `/register`, `/refresh`, `/logout`) are excluded to avoid recursion loops.
- **Strict CORS Origin Restriction**: The backend pins allowed origins to the authorized frontend URL (`http://localhost:5173`) with `allowCredentials = true`. Wildcard origins (`*`) are prohibited.
- **Role-Aware UI vs Authoritative Server Enforcement**: Frontend route guards (`ProtectedRoute`, `PublicOnlyRoute`) provide clean UI redirection, while all financial actions and administrative operations remain strictly enforced by the Spring Security backend.

---

## 4. Standardized Error Handling for Security (RFC 9457)

All authentication and authorization failures return standardized RFC 9457 Problem Details (`application/problem+json`):
- **401 Unauthorized (`AUTHENTICATION_REQUIRED`)**: Missing or malformed Bearer token on protected endpoints.
- **401 Unauthorized (`INVALID_CREDENTIALS`)**: Incorrect email/password combination or inactive account. Detail: `"Invalid email or password."`.
- **401 Unauthorized (`INVALID_REFRESH_TOKEN`)**: Expired, revoked, invalid, or missing refresh token.
- **403 Forbidden (`ACCESS_DENIED`)**: Authenticated user lacking the necessary role (e.g. Customer accessing `/api/ops/**`).
- **400 Bad Request (`EMAIL_ALREADY_REGISTERED`)**: Attempt to register an existing email.
- **400 Bad Request (`VALIDATION_FAILED`)**: Invalid fields or attempted self-registration with `ROLE_OPS`.

---

## 5. Webhook Security & Tampering Prevention (Phase 22)

- **HMAC-SHA256 Signature Verification**: Inbound provider callbacks (`POST /api/provider/webhooks`) are authenticated via `X-PSP-Webhook-Signature: sha256=<64 lowercase hex>`. The signature is computed over canonical bytes:
  $$\text{canonicalBytes} = \text{UTF-8}(\text{timestamp}) + \text{UTF-8(".")"} + \text{rawBodyBytes}$$
  Signatures are validated using constant-time `MessageDigest.isEqual` to prevent timing attacks.
- **Timestamp Windows & Replay Protection**:
  - The inbound request must supply `X-PSP-Webhook-Timestamp` containing the UTC epoch seconds of transmission.
  - The timestamp is validated using overflow-safe `Instant` arithmetic:
    $$\text{earliest} = \text{now} - \text{maxSkew}; \quad \text{latest} = \text{now} + \text{maxSkew}$$
  - Requests with timestamps outside the skew window (default 300 seconds) are rejected with `401 Unauthorized`.
  - Non-numeric timestamps, out-of-range values, or malformed signature formats return `401 Unauthorized`.
- **Shared Secret Integrity & Isolation**:
  - Webhook secret is configured via `PSP_WEBHOOK_SECRET` / `ledgerguard.psp.webhook.secret`.
  - Enforced at startup (`WebhookSecurityProperties` in API and `ProviderWebhookDispatcher` in PSP simulator) with strict `@PostConstruct` validation requiring at least 32 bytes (256 bits) of secret material.
  - Authentication failure responses never echo, leak, or expose the shared secret.
- **Durable Ingress & Deduplication**:
  - Webhook requests are persisted durably in `provider_events` using PostgreSQL `ON CONFLICT DO NOTHING` before local business processing.
  - Duplicate deliveries with matching semantic identity return `200 OK` with 0 duplicate financial executions.
  - Conflicting payloads or illegal sequence claims return `409 Conflict`.

---

## 6. Audit Logging & Sensitive Data Masking

- **Operations Audit Trail (Phase 28)**: Privileged administrative actions performed on reconciliation cases (`claimCase`, `resolveManually`) and balance snapshots (`repairSnapshot`, `ALREADY_CONSISTENT`) generate immutable rows in `audit_events`.
  - **Database Immutability Triggers**: PostgreSQL trigger `trg_audit_events_immutability` strictly rejects `UPDATE`, `DELETE`, and `TRUNCATE` operations on `audit_events`.
  - **Database Authoritative Timestamp**: The database `DEFAULT NOW()` supplies `occurred_at`. Application inserts strictly omit `occurred_at` to prevent application clock skew or tampering.
  - **Propagation.MANDATORY Atomicity**: `AuditService` executes with `Propagation.MANDATORY`. If the surrounding business transaction fails or conflicts, the audit row rolls back; if the audit insert fails, the business mutation rolls back.
  - **Strong Typing & Structured Payload**: Public methods accept strongly typed domain enums (`AuditAction`, `AuditTargetType`) and UUIDs. Details JSON is constructed internally from typed fields (zero raw `Map<String, Object>` ingress).
- **Scope Alignment / Account Freeze Deferral (Human-Approved Option A)**:
  - Phase 28 freeze/unfreeze was deferred by human architectural decision.
  - Current ACTIVE/DISABLED state is authentication status only. There is no administrative account-freeze workflow.
  - Existing access JWTs are not immediately revoked by user status changes; access JWT TTL remains approximately 15 minutes and tokens expire naturally.
  - No per-request user DB lookup was introduced because that would conflict with Phase 27 overload/backpressure guarantees.
  - Account freeze and unfreeze administrative endpoints and stateful token revocation filters were deferred from Phase 28 per human approval.
  - Existing disabled user enforcement in Section 3 remains authoritative: accounts with `status = DISABLED` are rejected on login (401) and cannot rotate refresh tokens (401).
- **Sensitive Data Logging Policy**:
  - Passwords, JWT secrets, full payment card numbers, bank account numbers, and webhook secrets are strictly prohibited from log files.
  - An exhaustive codebase audit across all microservices verified that sensitive parameters (`password`, `jwtSecret`, `webhookSecret`, `tokenHash`) are never printed in logger statements or serialized into public error representations.
  - User identifiers, correlation IDs, and transaction IDs are logged for traceability without exposing personally identifiable information (PII).

---

## 7. Rate Limiting & Denial of Service Protection

- **Token-Bucket Admission Control (Phase 27)**: Implemented using Bucket4j 8.19.0 (`bucket4j_jdk17-core`) paired with a bounded in-memory Caffeine cache (`maxEntries = 10000`, `idleTtl = 1h`). Filter positioned after Spring Security `AuthorizationFilter`.
- **Security Precedence**: Authentication (401) and role-based authorization (403) strictly precede token bucket evaluation. Unauthenticated callers and forbidden access attempts are rejected prior to filter execution and never consume token quota.
- **Keying & Partitioning**:
  - `PUBLIC_AUTH` (`/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout`): Keyed strictly by client remote IP (`PUBLIC_AUTH:ip:<ip>`), 10 tokens / 1 min greedy capacity. Mitigates credential-stuffing and brute-force attacks against BCrypt hashing.
  - `FINANCIAL_WRITE` (`POST /api/transfers`, `POST /api/payments`, `POST /api/payments/*/refund`, `POST /api/funding`, `POST /api/payouts`): Keyed by authenticated JWT subject UUID (`FINANCIAL_WRITE:user:<uuid>`), 20 tokens / 1 min greedy capacity.
  - `OPS` (`/api/ops/**`, `/api/reconciliation/**`): Keyed by authenticated JWT subject UUID (`OPS:user:<uuid>`), 30 tokens / 1 min greedy capacity.
  - `AUTHENTICATED_GENERAL`: Keyed by authenticated JWT subject UUID (`AUTHENTICATED_GENERAL:user:<uuid>`), 50 tokens / 1 min greedy capacity.
  - `EXEMPT`: `OPTIONS` preflight, `/actuator/health/**`, `/actuator/info`, and inbound PSP webhooks (`POST /api/provider/webhooks`) bypass rate limiting.
- **Bounded Concurrency & Backpressure**:
  - Embedded Tomcat worker threads: `max = 50`, `min-spare = 10`, `max-queue-capacity = 50`, `accept-count = 50`, `max-connections = 1000`.
  - HikariCP database pool bound: `maximum-pool-size = 10`.
  - Kafka consumer backpressure on `notification-worker`: `listener.concurrency = 3`, `consumer.max-poll-records = 10`.
- **Standardized 429 Error Response**: Exceeded quotas return RFC 9457 `application/problem+json` with `RATE_LIMIT_EXCEEDED` error code and integer `Retry-After` header. Zero financial side effects or idempotency record poisoning.

---

## 8. Security Headers & Input Hardening (Phase 28)

- **HTTP Security Response Headers**: Configured explicitly in Spring Security `SecurityConfig`:
  - `Content-Security-Policy`: `default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'`. Enforces zero execution or injection of unauthorized scripts, framing, or form targets.
  - `Strict-Transport-Security`: `max-age=31536000; includeSubDomains`. Enforces HTTPS transport with 1-year HSTS on all secure requests.
  - `X-Content-Type-Options`: `nosniff`. Prevents MIME-type confusion attacks.
  - `X-Frame-Options`: `DENY`. Mitigates clickjacking across all pages and API endpoints.
  - `CORS Headers`: Origin restricted strictly to the configured frontend host (`http://localhost:5173`) with `allowCredentials = true`, `maxAge = 3600s`, and explicit exposure of `Retry-After` for rate-limiting client integration.
- **Input Hardening & Control Character Sanitization**:
  - Administrative resolution notes on reconciliation cases undergo raw control character validation before any trimming or whitespace stripping.
  - Rejects C0 control characters (ASCII 0x00 through 0x1F, including NUL, CR, LF, TAB) and DEL (0x7F) with `INVALID_RECONCILIATION_OPERATION` (HTTP 400).
  - Enforces non-blank validation and maximum length constraint (1,000 characters) on normalized input.

---

## 9. Distributed Tracing & Correlation Header Sanitization (Phase 30)

- **Correlation ID Header Sanitization (`X-Correlation-Id`)**:
  - Inbound correlation IDs are validated against strict alphanumeric and safe symbol bounds: `^[a-zA-Z0-9_-]{1,64}$` (or standard UUID format).
  - If the header is missing, blank, longer than 64 characters, or contains CRLF characters, null bytes, quotes, HTML tags, or forbidden symbols, it is rejected and replaced with a clean server-generated UUIDv4.
  - Mitigates CRLF injection in logs and HTTP response headers.
- **Response Header Echoing & CORS Exposure**:
  - The sanitized correlation ID is set in the HTTP response header `X-Correlation-Id`.
  - Configured in CORS `exposedHeaders`: `Retry-After, X-Correlation-Id`, allowing frontend clients to correlate API responses with user actions.
- **MDC Isolation & Thread Safety**:
  - `CorrelationIdFilter` places `correlationId` into SLF4J MDC at servlet entry before Spring Security filters.
  - Guaranteed cleanup in `finally` block (`MDC.remove("correlationId")`) prevents thread-pool leakage across pooled Tomcat threads.
  - Similarly, Kafka consumer listeners (`DomainEventListener`) extract `X-Correlation-Id` into MDC and clean up in `finally`.
- **Zero PII & Metric Cardinality Protection**:
  - Distributed tracing spans capture HTTP paths, Kafka topics, and operation types. Account balances, customer names, passwords, tokens, and payment card details are never included in span attributes or trace events.
  - Trace IDs, span IDs, and correlation IDs are excluded from Prometheus metric labels, preventing high-cardinality label explosion.

---

## 10. Observability Infrastructure Security & Local Grafana Authentication (Phase 31)

- **Grafana Authentication Enforcement**:
  - Anonymous access is strictly disabled (`GF_AUTH_ANONYMOUS_ENABLED=false`).
  - Grafana does not permit unauthenticated access to dashboards, datasources, or settings (returns HTTP 401).
  - Administrator authentication requires explicit credentials configured via `.env` (`GRAFANA_ADMIN_PASSWORD`). No default or fallback password is committed or allowed in Docker Compose.
- **Prometheus HTTP Surface Hardening**:
  - `--web.enable-lifecycle` is removed and disabled, preventing unauthorized reload (`POST /-/reload`) or termination (`POST /-/quit`) requests.
  - Administrative APIs (`--web.enable-admin-api`), remote write endpoints, and OTLP ingestion receivers are disabled.
  - Prometheus scrape target is restricted to `host.docker.internal:8080/actuator/prometheus`.
- **Telemetry Isolation Boundary**:
  - Prometheus and Grafana are strictly local development observability tools and are isolated from the financial transaction execution path.
  - Telemetry collection is read-only; an outage, restart, or configuration error in Prometheus or Grafana cannot alter or compromise LedgerGuard financial invariants or ledger state.
  - Not designed or intended as a hardened public internet deployment.

---

## 11. Money Integrity Failure Lab Security & Isolation Boundary (Phases 32–33)

The Failure Lab execution engine and web operations console are designed with a **fail-closed, defense-in-depth security model** to guarantee zero blast radius against production systems, accounts, and databases:

1. **Deny-by-Default Environment Gate (`EnvironmentGuard` & `LabDatabaseTarget`)**:
   - Before executing any scenario step or fault injection, `EnvironmentGuard.assertLabEnvironment()` strictly verifies the database connection metadata.
   - Requires a valid cryptographically signed `LabDatabaseTarget` ownership token for intrusive mutations (such as `SnapshotFaultInjector`).
   - Unconditionally rejects any target database matching production or staging keywords (`prod`, `staging`, `live`, `primary`), public hostnames, or non-local IP addresses (e.g. `10.0.0.8`).
2. **Loopback-Only Interface Binding (`127.0.0.1:8083`)**:
   - `FailureLabApplication` binds exclusively to the local loopback interface `127.0.0.1`.
   - External network requests or remote incoming connections are dropped at the TCP socket layer.
3. **Dedicated Ephemeral Testcontainers Infrastructure**:
   - The lab executes against dedicated, disposable PostgreSQL 17.11 and Kafka 4.3.1 Testcontainers instances started specifically for the lab.
   - Ephemeral containers run Flyway migrations V1–V17 on startup and are automatically destroyed upon process termination.
   - Zero connection or data access to local developer databases (`ledgerguard_db`, `ledgerguard`).
4. **Dynamic Cryptographic Secrets (`SecureRandom`)**:
   - All runtime credentials (ephemeral database password, JWT signing secret, webhook verification secret) are generated dynamically at startup via `SecureRandom` Base64 encoding.
   - Zero hardcoded passwords, tokens, or credentials exist in source code or configuration files.
5. **Strict CORS Policy & Closed Mutation Surface**:
   - CORS is restricted exclusively to the Vite development server origins (`http://localhost:5173` and `http://127.0.0.1:5173`).
   - The mutation API strictly accepts `application/json` with a closed enum `ScenarioId` (`OPPOSING_TRANSFERS`, `TIMEOUT_AFTER_COMMIT`, `CORRUPTED_SNAPSHOT`, `WEBHOOK_RACE`).
   - Rejects arbitrary SQL, arbitrary JDBC URLs, arbitrary provider URLs, or custom executable script payloads.
6. **Role-Based Access Control (RBAC) & UX Navigation**:
   - Navigation links in `AppLayout` and the web operations route `/app/failure-lab` are protected by `OpsRoute`.
   - Accessible strictly to users authenticated with role `OPS`; non-OPS users are redirected to `/app` with zero exposure of chaos execution controls.
7. **Dual SecurityFilterChain Architecture**:
   - `failureLabSecurityFilterChain` (`@Order(1)`): Matches `/api/lab/**` exclusively with `cors(withDefaults())` and `permitAll()`. Provides an unauthenticated local control surface bound strictly to `127.0.0.1:8083`.
   - `securityFilterChain` (`@Order(2)`): Standard production security chain inherited via `@Import(LedgerGuardApplication.class)`. Any normal LedgerGuard application endpoints registered on port 8083 (e.g. `/api/transfers/**`, `/api/payouts/**`) remain protected by JWT authentication and RBAC; unauthenticated requests receive HTTP 401 Unauthorized.
   - Zero production chaos endpoints are introduced into `ledgerguard-api` or exposed on production port 8080.
8. **CORS as Browser Policy**:
   - CORS is an enforcement mechanism implemented by web browsers to restrict cross-origin requests; it is not backend authentication.
   - Backend security relies upon loopback-only binding (`127.0.0.1`), explicit CLI enablement (`ledgerguard.lab.enabled=true`), closed scenario enum dispatch, and complete ephemeral database isolation.

---

## 12. Container & Production Deployment Security (Phase 35)

Phase 35 establishes a defense-in-depth security model for containerized production execution across all microservices and web components:

### 1. Non-Root Execution & Least Privilege
- **Java Microservices**: Every backend container image (`ledgerguard-api`, `psp-simulator`, `notification-worker`) provisions a dedicated non-login system user and group (`ledgerguard:ledgerguard`, `UID 1001`, `GID 1001`). Processes execute strictly as `USER ledgerguard:ledgerguard`.
- **Frontend Nginx**: Runs under the pre-configured unprivileged `nginx` user (`UID 101`, `GID 101`) in `nginxinc/nginx-unprivileged:1.27-alpine`. The web server binds to unprivileged port `8080` rather than standard privileged port `80`.
### 2. Service Isolation & Network Segmentation
- **Private Bridge Network**: All inter-service traffic routes exclusively through `ledgerguard-prod-network`. Containers communicate via DNS service names (`postgres`, `kafka`, `ledgerguard-api`, `psp-simulator`, `notification-worker`, `prometheus`, `grafana`).
- **PostgreSQL Access Control**:
  - PostgreSQL revokes public database connection rights (`REVOKE CONNECT ON DATABASE ... FROM PUBLIC`).
  - Three isolated logical databases (`ledgerguard`, `psp_simulator`, `notification_worker`) are accessed using dedicated database credentials (`ledgerguard_app`, `psp_simulator_app`, `notification_worker_app`).
  - Microservices cannot access or query tables belonging to peer microservices.
- **Port Exposure Policy**: Databases (`postgres`) and message brokers (`kafka`) do not publish host ports, remaining purely internal to the Docker network.

### 3. Stateful Volume Integrity
- Stateful data directories (`/var/lib/postgresql/data`, `/var/lib/kafka/data`, `/prometheus`, `/var/lib/grafana`) are backed by dedicated named Docker volumes, ensuring persistence and permissions integrity across restarts.

### 4. Secret Hygiene & Configuration Safety
- **No Hardcoded Secrets**: Container images and Compose files contain zero hardcoded passwords, tokens, or encryption keys.
- **Environment Template (`.env.prod.example`)**: Documents all required security variables with blank placeholder values.
- **Git & Build Context Protection**:
  - `.gitignore` prevents real `.env*` secret files from entering version control.
  - `.dockerignore` files prevent local environment secrets, development keystores, `.git` history, and build artifacts from leaking into container images during `docker build`.

---

## 13. Edge Reverse Proxy & SSL Security (Phase 36)

Phase 36 establishes an external security perimeter using Nginx as an unprivileged edge reverse proxy gateway (`nginx-edge`):

### 1. TLS Termination & Certificate Hygiene
- **SSL Termination**: Terminates TLSv1.2 and TLSv1.3 protocols on port `8443` (mapped to host `443`), using secure cipher suites (`HIGH:!aNULL:!MD5`).
- **External Key Mounting**: Certificates (`server.crt`) and private keys (`server.key`) are mounted read-only at runtime into `/etc/nginx/certs:ro` from the host path `./infrastructure/nginx/certs`.
- **Zero Committed Private Keys**:
  - `.gitignore` ignores `infrastructure/nginx/certs/*` while tracking `.gitkeep`.
  - `.dockerignore` excludes `infrastructure/nginx/certs` from all container build contexts.
  - Validated zero presence of `BEGIN PRIVATE KEY`, `BEGIN RSA PRIVATE KEY`, or `BEGIN EC PRIVATE KEY` in version control.

### 2. Edge Defensive Headers
All HTTP and HTTPS responses from the edge gateway emit defensive headers:
- `X-Frame-Options: DENY` (mitigates clickjacking across all views).
- `X-Content-Type-Options: nosniff` (prevents MIME-type confusion attacks).
- `Referrer-Policy: strict-origin-when-cross-origin` (restricts referrer data to same-origin requests).
- `Permissions-Policy: geolocation=(), microphone=(), camera=()` (disables unused browser device features).
- `server_tokens off;` (suppresses Nginx version disclosures in HTTP Server headers).

### 3. Localhost HSTS Policy
- **Intentional Omission on Localhost**: `Strict-Transport-Security` (HSTS) is intentionally omitted for the local self-signed development and testing stack. Enforcing a multi-month or 1-year HSTS header on `localhost` risks persistent local browser lockout with self-signed certificates.
- **Production Guidance**: For real public domain deployment with trusted CA certificates (e.g. Let's Encrypt / ACME), HSTS should be enabled with `add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;`.

### 4. Layered Edge Rate Limiting & DoS Defense
- **Nginx Edge Zones**: Coarse per-IP rate limiting using shared memory zones:
  - General API (`/api/`): `30r/s`, `burst=50 nodelay`.
  - Authentication API (`/api/auth/`): `10r/s`, `burst=10 nodelay`.
  - Edge Rejection: Exceeded limits immediately return HTTP `429 Too Many Requests`.
- **Spring Bucket4j Integration**: Edge rate limits serve as first-line volumetric DDoS defense. Spring Boot Bucket4j filters continue to enforce application-aware, tenant-specific, and authenticated-principal business quotas.

### 5. Secure Cookie & Surface Minimization
- **Cookie Security**: Refresh tokens continue to use `ResponseCookie` with `secure: true`, `HttpOnly: true`, and `SameSite: Strict`. HTTPS (`https://localhost`) is the authoritative authenticated browser path.
- **Actuator Blocking**: `/actuator/**` paths are blocked at the public edge with HTTP 404, preventing unauthorized inspection of health, metrics, or env endpoints from the internet. Internal Prometheus scraping continues unhindered via the Docker network.
- **Port Minimization**: Host port publishing is removed from `ledgerguard-api` and `ledgerguard-web`, isolating backend containers from direct external socket binding.
