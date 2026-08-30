# ADR-004: Precise Money Representation (Currency + Minor Units)

## Status
Accepted

## Context
Representing financial amounts using floating-point types (`float`, `double` in Java, or `FLOAT`/`REAL` in SQL) leads to binary rounding errors (e.g., $0.1 + 0.2 = 0.30000000000000004$) due to IEEE 754 floating-point representation. Over thousands of financial transactions, fractional cents or paise are created or lost, violating the central money conservation invariant.

While Java provides `BigDecimal`, its usage in databases can introduce performance overhead, variable decimal scaling bugs (e.g., scale of 2 vs scale of 4), and awkward SQL aggregations if scale is not strictly fixed.

## Decision
We represent all monetary amounts using a dedicated **`Money`** value object:
```java
public record Money(
    Currency currency,
    long minorUnits
) implements Comparable<Money> { ... }
```

- **Minor Units Representation**: All amounts are stored as exact 64-bit signed integers (`BIGINT` in PostgreSQL and `long` in Java) representing the currency's smallest indivisible unit (e.g., paise for `INR`, cents for `USD`). For example, ₹500.25 is stored as `50025`.
- **Database Column**: Database tables store minor units as `BIGINT NOT NULL` and currency codes as `VARCHAR(3) NOT NULL`.
- **Single-Currency Consistency**: Operations involving two `Money` instances (addition, subtraction, comparison) must assert matching currencies; multi-currency arithmetic without explicit conversion throws an exception.
- **Prohibition of Floating Point**: Primitives `float` and `double` are strictly prohibited in domain and persistence models.

## Alternatives Considered
1. **Java `BigDecimal` with SQL `DECIMAL(19, 4)`**:
   - *Rejected*: Allows arbitrary decimal scale variations and requires careful scale-matching during equality comparisons. Storing integer minor units eliminates scale ambiguity entirely.
2. **IEEE 754 `double` / `Double`**:
   - *Rejected*: Inherent precision loss makes floating-point primitives completely unacceptable for financial accounting.

## Consequences
- **Positive**:
  - Zero rounding or representation errors; arithmetic is exact.
  - Native database performance: fast integer arithmetic and standard `BIGINT` indexing.
  - Clear semantic separation between presentation (formatted currency strings) and domain calculation (integer minor units).
- **Negative**:
  - Requires conversion logic at API boundaries when formatting amounts for human display (e.g., dividing minor units by 100).

## Trade-offs
We trade minor formatting overhead at the API boundary for zero floating-point drift and rock-solid integer mathematical precision.
