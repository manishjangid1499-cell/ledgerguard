# ADR-008: External PSP Boundary & Realistic Failure Simulation

## Status
Accepted

## Context
Interacting with external Payment Service Providers (PSPs), card networks, and banking rails introduces unpredictable network latency, partial failures, and ambiguous outcomes. An external API call cannot join a local PostgreSQL ACID transaction.

Common naive implementations simply mock external payment gateways with static `HTTP 200 OK` responses in tests. This hides the most difficult real-world financial engineering problems:
- What happens if the network times out *after* the external bank has debited the customer?
- What happens if a webhook arrives before the synchronous API response returns?
- What happens if a provider delivers duplicate or out-of-order settlement callbacks?

## Decision
We establish the **External PSP as an Independent Deployable Boundary** backed by a dedicated **`psp-simulator`**:

1. **Physical Process & Database Isolation**:
   - `psp-simulator` is a distinct Spring Boot service with its own independent PostgreSQL database (`psp_db`).
   - It cannot share database connections, tables, or transactions with `ledgerguard-api`.
2. **Deterministic Fault Injection Modes**:
   - The simulator supports programmatic scenario configurations, including:
     - `NORMAL_SUCCESS`: Clean processing with standard latency.
     - `FAIL_BEFORE_PROCESSING`: Provider rejects immediately with HTTP 4xx/5xx.
     - `TIMEOUT_BEFORE_PROCESSING`: Connection drops before provider takes action.
     - `TIMEOUT_AFTER_SUCCESS`: Provider successfully commits external payout/charge, but network drops before returning HTTP response to LedgerGuard.
     - `DELAYED_RESPONSE` & `DELAYED_WEBHOOK`: Provider completes action but delays asynchronous callback.
     - `DUPLICATE_WEBHOOK`: Provider sends identical webhook payloads multiple times.
     - `OUT_OF_ORDER_WEBHOOK`: Provider sends status updates out of chronological sequence.
3. **Ambiguity Handling in LedgerGuard Core**:
   - In response to timeouts or network drops, `ledgerguard-api` must **never** prematurely assume failure.
   - Operations transition to `UNKNOWN` or `RECONCILIATION_REQUIRED`, holding funds securely until status polling, webhook receipt, or external reconciliation proves provider state.

## Alternatives Considered
1. **In-Memory WireMock / Static Mocking**:
   - *Rejected*: Lacks independent persistence, cannot simulate real multi-stage asynchronous callbacks with out-of-order state transitions, and prevents realistic end-to-end chaos testing.
2. **Direct Integration with Live Sandbox Gateways (e.g., Stripe/Razorpay Sandbox)**:
   - *Rejected*: Sandbox environments do not allow programmatic injection of network drops *after* provider commit, rate limits interfere with load testing, and external dependencies undermine reproducible automated test suites.

## Consequences
- **Positive**:
  - Provides a realistic, reproducible playground for testing distributed failure ambiguity.
  - Forces application code to properly handle `UNKNOWN` states, balance holds, and reconciliation queues.
  - Completely isolated from live financial networks.
- **Negative**:
  - Requires maintaining a second Spring Boot application and database schema in Docker Compose and Testcontainers.

## Trade-offs
We accept the overhead of maintaining an independent simulator deployable in exchange for the ability to realistically test distributed ambiguity and prove money integrity under external failure modes.
