# ADR-007: Transactional Outbox for Post-Commit Event Delivery

## Status
Accepted

## Context
When a financial transaction completes (e.g., `TransferCompleted`, `PaymentCompleted`), downstream systems such as notification workers, audit subscribers, or data warehouses must be notified. 

Publishing directly to a message broker (such as Apache Kafka) from inside the application service method introduces the classic **Dual-Write Problem**:
- If the application writes to the database first and then attempts to send to Kafka, a network failure or JVM crash before publishing results in lost messages.
- If the application sends to Kafka first and then attempts to commit to the database, a database rollback results in "ghost events" where downstream systems react to financial transactions that never actually occurred.

## Decision
We implement the **Transactional Outbox Pattern** with PostgreSQL and Apache Kafka:

1. **Atomic Insertion**:
   - Every financial domain event is inserted into the `outbox_events` table within the **exact same PostgreSQL database transaction** as the financial state changes (ledger entries, transfer records, balance updates).
   - If the business transaction rolls back, the outbox record rolls back automatically.
2. **Asynchronous Polling & Publication**:
   - A dedicated background publisher (`OutboxPublisherWorker`) polls for records in `PENDING` state.
   - For multi-worker safety, poller queries utilize PostgreSQL's non-blocking row-claiming syntax:
     ```sql
     SELECT * FROM outbox_events 
     WHERE status = 'PENDING' AND (locked_until IS NULL OR locked_until < NOW())
     ORDER BY created_at ASC
     LIMIT 50
     FOR UPDATE SKIP LOCKED;
     ```
3. **Kafka Delivery**:
   - The publisher sends the event to the appropriate Kafka topic and marks the outbox row as `PUBLISHED` upon receiving the Kafka broker acknowledgment.
   - Transient publication failures trigger exponential backoff retries. Unrecoverable failures transition to `DEAD` for operational triage.
4. **Kafka Boundary Rule**:
   - Kafka is strictly an asynchronous post-commit transport. Kafka is **never** the authoritative source of truth for financial transactions.

## Alternatives Considered
1. **Direct Synchronous Kafka Publishing in Transaction**:
   - *Rejected*: Suffers from the dual-write problem, can publish uncommitted ghost events, and holds database connections open during Kafka network I/O.
2. **Debezium / CDC (Change Data Capture)**:
   - *Considered for Future*: While CDC works well, it adds significant operational overhead (Kafka Connect infrastructure) for a portfolio/monolith deployment. An application-level outbox with `SKIP LOCKED` provides identical transactional guarantees with zero external operational dependencies.

## Consequences
- **Positive**:
  - Guaranteed at-least-once message delivery to Kafka without dual-write inconsistencies.
  - Zero ghost events from rolled-back database transactions.
  - Decouples core financial throughput from Kafka broker availability.
- **Negative**:
  - Introduces slight asynchronous publication latency (typically milliseconds) between database commit and Kafka consumption.
  - Requires scheduled maintenance or archival for published outbox event rows.

## Trade-offs
We accept minor publication latency in exchange for eliminating dual-write failure modes and guaranteeing event consistency.
