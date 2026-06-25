# Phase 3 — Implementation

## What Was Built

A Spring Boot 3.5 REST API for sending and tracking notifications (Email / SMS).

## Layer Breakdown

| Layer | Key Classes | Notes |
|---|---|---|
| Controller | `NotificationController` | 7 endpoints under `/api/notifications` |
| Service | `NotificationService` | Business logic, cache coordination, MQ publish |
| Core Service | `NotificationCoreService` | DB access wrapper; centralises null-safety and defaults |
| Mapper | `NotificationMapper` | MyBatis annotation SQL; `@Timed` on every method |
| Redis Util | `NotificationRedisUtil` | Cache, recent list (Redis List), idempotency (SET NX) |
| Converter | `NotificationConverter` | Static Entity → DTO; extracted to `common/converter/` |
| Scheduler | `NotificationScheduler` | Polls every 60 s for SCHEDULED notifications; sweeper every 120 s for stuck PENDING |

## Cross-Cutting Infrastructure

| Class | Package | Purpose |
|---|---|---|
| `CorrelationIdFilter` | `common/filter/` | Injects `X-Correlation-ID` into MDC + response header |
| `RateLimitInterceptor` | `common/interceptor/` | Global 200 req/s + per-IP per-path fixed-window limiter |
| `RequestLoggingInterceptor` | `common/interceptor/` | Logs method, path, status, elapsed time |
| `GlobalExceptionHandler` | `common/exception/` | Maps all exceptions to `ErrorResponse` |
| `NotificationRequestValidator` | `validator/` | Custom type/field validation beyond `@Valid` |

## Key Design Decisions

- **Soft delete** — `deleted_at` column; UPDATE WHERE includes `AND deleted_at IS NULL` to prevent update-after-delete race.
- **Idempotency** — Redis atomic `SET NX` claims a slot before insert; resolves to real ID after commit; 24 h TTL.
- **SCHEDULED status** — distinct from `PENDING` so callers can tell deferred notifications apart at a glance.
- **Rate limiting in interceptor, not service** — infrastructure concern kept out of business logic; in-memory fixed window avoids Redis round-trip.
- **No custom `ObjectMapper` bean** — Spring Boot auto-configures `JavaTimeModule`; `LocalDateTime` serialisation controlled via `application.yaml`.
- **Stuck PENDING recovery** — `create` cannot wrap DB + MQ in one transaction (RocketMQ is outside JTA). Instead a sweeper job runs every 120 s, finds PENDING rows older than 5 minutes, and marks them FAILED. They can then be retried via `POST /{id}/retry`. Transactional Outbox is the production-grade solution but out of scope here.

## Cache Protection (Post-Implementation Additions)

Three classic Redis cache problems addressed in `NotificationRedisUtil` + `NotificationService`:

| Problem | Risk | Solution |
|---|---|---|
| 雪崩 Avalanche | Low | `cacheNotification` TTL = 60 ±10 s (random jitter via `ThreadLocalRandom`) |
| 穿透 Penetration | Low–Med | `cacheNotFound` stores `__NULL__` sentinel for 30 s; `findById` checks `isNullCached` before hitting DB |
| 擊穿 Breakdown | Low | `findById` acquires Redis `SET NX` mutex (5 s TTL) on cache miss; contended requests spin-wait up to 150 ms then fall back to DB |

`findById` flow after all three protections:
```
cache hit          → return immediately
null cached        → throw NotificationNotFoundException (no DB)
lock acquired      → double-check cache → load from DB → release lock
lock not acquired  → retry cache up to 3× (50 ms apart) → fallback to DB
```
