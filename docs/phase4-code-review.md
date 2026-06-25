# Phase 4 — Code Review

14 issues found and fixed across two review rounds.

## Issues Fixed

### 1. Controller base path missing `/api` prefix (Critical)
`@RequestMapping("/notifications")` → `@RequestMapping("/api/notifications")`

Interceptors were registered at `/api/**` but the controller was at `/notifications`, so `RateLimitInterceptor` and `RequestLoggingInterceptor` never fired for any request.

---

### 2. Update + delete race condition
`UPDATE ... WHERE id = #{id}` → `UPDATE ... WHERE id = #{id} AND deleted_at IS NULL`

Without the guard, an update racing against a concurrent soft-delete could un-delete the record by writing a new `updated_at` without a `deleted_at`.

---

### 3. Idempotency claim not atomic
`storeIdempotencyKey()` did a read-then-write, leaving a window where two concurrent requests with the same key both saw "not exists" and both proceeded to insert.

Replaced with `tryClaimIdempotencyKey()` using Redis `SET NX` (atomic), shrinking the race window to near-zero.

---

### 4. No way to distinguish scheduled vs in-flight notifications
`status = PENDING` was used for both deferred notifications (with `scheduledAt`) and live ones. Callers couldn't tell them apart from the response.

Added `NotificationStatus.SCHEDULED`; mapper query changed from hardcoded `'PENDING' AND scheduled_at IS NOT NULL` to parameterised `status = #{status}`.

---

### 5. Implicit else in `updateTracking`
```java
// Before — silent no-op if event is neither DELIVERED nor READ
if ("DELIVERED".equals(event)) { ... }
else if ("READ".equals(event)) { ... }
// no else → unknown events silently ignored
```
Replaced with explicit `switch (TrackingEvent.valueOf(...))` + `default → throw`, so future unhandled enum values fail loudly.

---

### 6. Raw string comparison in validator
```java
// Before
"email".equals(req.type())

// After
NotificationType.EMAIL.name().equalsIgnoreCase(req.type())
```
Ties validation to the enum definition; case-insensitive to match how the service already handles type strings.

---

## Round 2 — Agent Review (code-reviewer + security-engineer)

### 7. Unbounded `X-Idempotency-Key` → Redis memory DoS
Added `@Validated` + `@Size(max = 64)` on the header parameter in `NotificationController`. Keys longer than 64 chars now return 400 before touching Redis.

### 8. Unbounded input fields
Added `@Size` constraints to `NotificationRequest` (recipient max 255, subject max 255, content max 50000) and `EmailOptionsDto` (cc/bcc max 50 items, attachmentUrls max 20 items).

### 9. `Long.parseLong` throws on malformed idempotency value
`getIdempotencyResult` wrapped in try-catch; malformed stored values return `Optional.empty()` instead of propagating `NumberFormatException`.

### 10. `HttpMessageNotReadableException` not handled → 500
Added handler in `GlobalExceptionHandler`; malformed JSON now returns 400 `INVALID_ARGUMENT`.

### 11. `ConstraintViolationException` not handled → 500
Added handler in `GlobalExceptionHandler` for method-parameter constraint violations (triggered by `@Validated` + `@Size` on header).

### 12. `RequestLoggingInterceptor` NPE on CORS preflight
`afterCompletion` cast `getAttribute(START_KEY)` to `long` without null check. Added null guard — if `preHandle` was skipped the method returns early.

### 13. Scheduler duplicate processing across instances
Added `tryAcquireSchedulerLock(id)` in `NotificationRedisUtil` (Redis `SET NX`, 120 s TTL). `NotificationScheduler` now claims each notification before processing; concurrent instances skip already-claimed rows.

### 14. `X-Forwarded-For` spoofable in rate limiter
Removed XFF trust entirely in `RateLimitInterceptor.resolveClientIp`; always uses `request.getRemoteAddr()`. No reverse proxy exists in this setup, so XFF is meaningless and only a spoofing vector.

### Not fixed — `@Transactional`
Reviewed and intentionally skipped. Each service method has at most one DB write; `@Transactional` adds no atomicity benefit. For `create` (insert → MQ → update), RocketMQ is outside the JTA boundary — the correct solution is a transactional outbox pattern, which is out of scope for this project.

### Removed — gRPC `dependencyManagement`
`io.grpc` 1.33.0 pins were in `pom.xml` but gRPC is not used anywhere in the source. Block removed entirely.
