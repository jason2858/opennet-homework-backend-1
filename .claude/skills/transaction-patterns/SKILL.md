---
name: transaction-patterns
description: Spring @Transactional best practices, anti-patterns, and pitfalls. Use when writing service layer logic, managing transaction boundaries, or debugging transaction-related bugs (self-invocation, rollback, propagation).
---

# Transaction Patterns Skill

Service-layer transaction management in Spring applications.

## When to Use
- Writing `@Transactional` service methods
- Debugging "transaction not working" / "rollback not happening"
- Designing service orchestration with multiple DB operations
- Questions about propagation, isolation, or rollback behavior

---

## Core Rules

| Rule | Why |
|------|-----|
| Transaction boundary belongs to **Service layer** | Controller and Repository should not own transactions |
| Never put `@Transactional` on Controller | Violates layered architecture |
| Keep transactions **short** | Long transactions hold DB connections and locks |
| Default `@Transactional` for writes | Ensures atomicity |
| `@Transactional(readOnly = true)` for reads | Skips dirty checking, improves performance |

---

## Anti-Pattern 1: External Calls Inside Transaction

```java
// ❌ BAD: HTTP/MQ/SMTP calls block the DB connection
@Transactional
public void createOrder(OrderRequest req) {
    Order order = orderRepository.save(new Order(req));
    paymentClient.charge(order.getTotal());     // HTTP — slow, may timeout
    emailService.sendConfirmation(order);        // SMTP — blocks
    kafkaProducer.send("order-created", order);  // Kafka — network I/O
}

// ✅ GOOD: DB only inside transaction, side effects outside
@Transactional
public Order createOrder(OrderRequest req) {
    return orderRepository.save(new Order(req));
}

public void processOrderCreated(Order order) {
    paymentClient.charge(order.getTotal());
    emailService.sendConfirmation(order);
    kafkaProducer.send("order-created", order);
}
```

**Inside transaction (OK):** Database read/write only
**Outside transaction:** HTTP calls, MQ publish, email, file upload

### Using @TransactionalEventListener for Clean Separation

```java
// ✅ Even better: event-driven, guaranteed to run after commit
@Service
public class OrderService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Order createOrder(OrderRequest req) {
        Order order = orderRepository.save(new Order(req));
        eventPublisher.publishEvent(new OrderCreatedEvent(order));
        return order;
    }
}

@Component
public class OrderEventHandler {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderCreated(OrderCreatedEvent event) {
        // Runs ONLY after transaction commits successfully
        kafkaProducer.send("order-created", event.order());
    }
}
```

---

## Anti-Pattern 2: Self-Invocation Trap

Spring `@Transactional` works via proxy. Internal method calls bypass the proxy.

```java
// ❌ BAD: @Transactional is ignored
@Service
public class OrderService {
    public void process(Long id) {
        this.updateStatus(id);  // direct call — NOT through Spring proxy!
    }

    @Transactional
    public void updateStatus(Long id) {
        // No transaction here!
    }
}

// ✅ FIX: Use a separate bean
@Service
public class OrderService {
    private final OrderStatusUpdater statusUpdater;

    public void process(Long id) {
        statusUpdater.updateStatus(id);  // goes through proxy
    }
}

@Service
public class OrderStatusUpdater {
    @Transactional
    public void updateStatus(Long id) {
        // Transaction works correctly
    }
}
```

---

## Anti-Pattern 3: Wrong Rollback Behavior

```java
// ❌ BAD: Checked exceptions do NOT trigger rollback by default
@Transactional
public void transfer(Long from, Long to, BigDecimal amount) throws InsufficientFundsException {
    accountRepository.debit(from, amount);
    if (balance < 0) throw new InsufficientFundsException();  // no rollback!
    accountRepository.credit(to, amount);
}

// ✅ FIX: Explicitly declare rollback for checked exceptions
@Transactional(rollbackFor = InsufficientFundsException.class)
public void transfer(Long from, Long to, BigDecimal amount) throws InsufficientFundsException {
    // now rolls back correctly
}
```

**Rollback rules:**
- `RuntimeException` → rolls back (default)
- Checked `Exception` → does NOT roll back (default)
- Use `rollbackFor` to override

---

## Read-Only Optimization

```java
// ✅ Hint to Hibernate: skip dirty checking, flush mode NEVER
@Service
@Transactional(readOnly = true)  // class-level default for reads
public class OrderQueryService {

    public List<OrderDTO> findRecentOrders(int days) {
        return orderRepository.findRecent(days).stream()
            .map(OrderDTO::from)
            .toList();
    }

    @Transactional  // override for write methods
    public Order createOrder(OrderRequest req) {
        return orderRepository.save(new Order(req));
    }
}
```

---

## Propagation Quick Reference

| Propagation | Behavior | Use When |
|-------------|----------|----------|
| `REQUIRED` (default) | Join existing or create new | Most cases |
| `REQUIRES_NEW` | Always create new, suspend existing | Independent operation (e.g., audit log) |
| `MANDATORY` | Must have existing, throw if none | Enforce caller provides transaction |
| `NOT_SUPPORTED` | Suspend existing, run without | Read-only operations that shouldn't lock |

```java
// Audit log must persist even if outer transaction rolls back
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logAudit(String action, Long entityId) {
    auditRepository.save(new AuditLog(action, entityId));
}
```

---

## Quick Checklist

- [ ] `@Transactional` on Service, not Controller
- [ ] Write methods use `@Transactional`, read methods use `@Transactional(readOnly = true)`
- [ ] No HTTP/MQ/email calls inside transaction
- [ ] No self-invocation of `@Transactional` methods
- [ ] Checked exceptions have `rollbackFor` if needed
- [ ] Transactions are as short as possible

---

---

## MyBatis Context

`@Transactional(readOnly = true)` has less impact with MyBatis than with JPA — there is no Hibernate dirty checking to skip. It still creates a read-only connection hint for the DB driver and is worth using for multi-statement reads that need a consistent snapshot.

**When to use `@Transactional` with MyBatis:**
- Multiple mapper writes that must be atomic (e.g., insert parent + insert child rows)
- Do NOT wrap single-mapper methods — they auto-commit and gain nothing

**When NOT to use `@Transactional`:**
- Sequences that mix DB writes with external calls (MQ, HTTP, SMTP) — see Anti-Pattern 1

### MQ + DB Eventual Consistency Pattern

This project uses the following sequence in `NotificationService.create()`:

```
mapper.insert(notification)      ← DB, auto-commits, gets ID
publishToMQ(notification)        ← external call, best-effort
notification.setStatus(SENT/FAILED)
mapper.update(notification)      ← DB, commits final status
```

**Why no `@Transactional` here:**
Adding it would hold the DB connection open during `publishToMQ()` — a network call that may be slow or fail. This is Anti-Pattern 1.

**Resilience via scheduler:**
- If `update()` fails after MQ succeeds: DB still shows PENDING → scheduler retries
- If MQ fails: status is set to FAILED, `update()` persists that → scheduler retries
- Accepted trade-off: transient inconsistency between MQ state and DB state, resolved by retry

---

## Related Skills

| Skill | When to load alongside this one |
|---|---|
| `coding-conventions` | MyBatis mapper + service layer patterns for this project |
| `web-infra` | If transaction failure should map to specific HTTP error codes |
