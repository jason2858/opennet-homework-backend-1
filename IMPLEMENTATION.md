# Notification Service

A RESTful notification service built with Spring Boot 3.5, supporting email and SMS delivery via RocketMQ, with Redis caching and MySQL persistence.

通知服務 REST API，支援 Email / SMS 發送，以 RocketMQ 傳遞事件，Redis 快取，MySQL 持久化。

---

## Tech Stack / 技術棧

| | |
|---|---|
| Runtime | Java 21 + Spring Boot 3.5.3 |
| Database | MySQL + MyBatis |
| Cache | Redis (StringRedisTemplate + Lua scripts) |
| Messaging | RocketMQ |
| Metrics | Micrometer + TimedAspect (`@Timed`) |
| Build | Maven |

---

## Architecture / 架構

```
HTTP Request
    │
    ├── RateLimitInterceptor   (per-IP + global 200 req/s)
    ├── RequestLoggingInterceptor
    │
    ▼
NotificationController
    │
    ▼
NotificationService          ← business logic, caching, MQ publish
    ├── NotificationCoreService  ← DB access wrapper (MyBatis)
    ├── NotificationRedisUtil    ← Redis operations
    └── RocketMQTemplate         ← publish to notification-topic
```

**Layered responsibilities / 分層職責**

| Layer | Class | Responsibility |
|---|---|---|
| Controller | `NotificationController` | HTTP I/O, request validation |
| Service | `NotificationService` | Business logic, cache coordination, MQ publish |
| Core Service | `NotificationCoreService` | DB access, null-safety, param defaults |
| Mapper | `NotificationMapper` | MyBatis SQL |
| Redis Util | `NotificationRedisUtil` | Cache, recent list, idempotency, mutex lock |
| Converter | `NotificationConverter` | Entity → DTO mapping |
| Scheduler | `NotificationScheduler` | Picks up SCHEDULED notifications every 60s; sweeper every 120s for stuck PENDING |

---

## Package Structure / 套件結構

```
com.example.demo/
├── controller/
├── service/
│   └── core/
├── repository/
├── model/
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   ├── entity/
│   ├── enums/
│   └── mq/
├── util/
├── constants/
├── common/
│   ├── converter/
│   ├── exception/
│   ├── filter/
│   ├── handler/
│   ├── interceptor/
│   └── typehandler/
├── config/
├── scheduler/
└── validator/
```

---

## Notification Status Lifecycle / 通知狀態流程

```
create (immediate)
  PENDING ──► SENT
           └► FAILED ──► (retry) ──► SENT
                                  └► FAILED

create (scheduled)
  SCHEDULED ──► (scheduler picks up) ──► SENT
                                      └► FAILED

tracking (after delivery)
  SENT ──► DELIVERED ──► READ
```

| Status | Meaning |
|---|---|
| `PENDING` | Created, publish in progress |
| `SCHEDULED` | Waiting for scheduled time |
| `SENT` | Successfully published to MQ |
| `FAILED` | MQ publish failed |

---

## API Reference / API 文件

Base URL: `http://localhost:8080`

### POST /api/notifications — Create / 建立通知

**Required fields / 必填欄位**
```json
{
  "type": "email",
  "recipient": "user@example.com",
  "content": "Thanks for signing up!"
}
```

**Optional fields / 選填欄位**

| Field | Type | Description |
|---|---|---|
| `subject` | string | Email subject；SMS 預設 `無主旨` |
| `scheduledAt` | ISO-8601 datetime | 排程發送，必須是未來時間 |
| `emailOptions.fromAddress` | string | 寄件者；預設 `no-reply@example.com` |
| `emailOptions.replyTo` | string | Reply-to address |
| `emailOptions.cc` | string[] | 副本收件人（需為合法 email） |
| `emailOptions.bcc` | string[] | 密件副本收件人 |
| `emailOptions.contentType` | string | `text/plain`（預設）或 `text/html` |
| `emailOptions.attachmentUrls` | string[] | 附件 URL 清單 |
| `smsOptions.senderId` | string | SMS 發送者 ID / 品牌名稱 |

**Response** `201 Created`
```json
{
  "id": 1,
  "type": "email",
  "recipient": "user@example.com",
  "status": "SENT",
  "scheduledAt": null,
  "sentAt": "2026-06-25T10:00:00",
  "createdAt": "2026-06-25T10:00:00"
}
```

Returns `200 OK` with `X-Idempotency-Replayed: true` if the same `X-Idempotency-Key` was seen before.

若帶有已使用過的 `X-Idempotency-Key`，回傳 `200 OK` + `X-Idempotency-Replayed: true`。

**Implementation note — DB/MQ consistency**

The create flow is `INSERT (PENDING)` → publish to RocketMQ → `UPDATE (SENT/FAILED)`. RocketMQ is outside the JTA transaction boundary, so `@Transactional` cannot guarantee consistency: if the MQ publish succeeds but the `UPDATE` fails, a rollback would undo the `INSERT` too — leaving a sent MQ event with no corresponding DB record, which is worse than a row stuck in `PENDING`.

For scenarios where duplicate or lost events are unacceptable (e.g. financial transactions, billing), the **Transactional Outbox Pattern** is the appropriate solution: instead of publishing to MQ directly, write an outbox event to the DB in the same transaction as the `INSERT`. A separate worker then reads the outbox, publishes to MQ, and deletes the outbox record — decoupling DB commit from MQ delivery so neither side can succeed without the other.

For this project, a sweeper job is used as a pragmatic alternative (see Scheduled Delivery section below).

實作說明：`create` 流程為 `INSERT (PENDING)` → 發 RocketMQ → `UPDATE (SENT/FAILED)`。RocketMQ 不在 JTA transaction 範圍內，加 `@Transactional` 反而更糟（MQ 送出後若 `UPDATE` 失敗，rollback 會連 `INSERT` 一起撤銷，造成無對應 DB 記錄的幽靈 MQ 事件）。

若涉及金融扣費、訂單等不允許重複或丟失事件的場景，可採用 Transactional Outbox Pattern：在同一個 DB transaction 裡同時寫入 notification 和 outbox event，再由獨立 worker 讀取 outbox 發送 MQ。此專案以 sweeper 作為務實替代。

---

### GET /api/notifications/{id} — Get by ID / 查詢單筆

Returns from Redis cache if available; falls back to MySQL and re-caches.

優先從 Redis 快取讀取，快取不存在時查 MySQL 並寫入快取。

**Response** `200 OK` — `NotificationResponse`

---

### GET /api/notifications/recent — Recent 10 / 最近 10 筆

Returns the 10 most recently created notifications from Redis list. Falls back to MySQL if cache is empty.

從 Redis List 讀取最近 10 筆；快取空時從 MySQL 重建。

**Response** `200 OK` — `NotificationResponse[]`

---

### PUT /api/notifications/{id} — Update / 更新

**Request**
```json
{
  "subject": "Updated subject",
  "content": "Updated content"
}
```

Re-publishes to RocketMQ. Evicts notification and recent-list cache.

重新發送 MQ 事件，清除該筆及最近清單的快取。

**Response** `200 OK` — `NotificationResponse`

---

### DELETE /api/notifications/{id} — Delete / 刪除

Soft-delete via `deleted_at`. Evicts cache. Returns `204 No Content`.

軟刪除（設定 `deleted_at`），清除快取，回傳 `204 No Content`。

---

### POST /api/notifications/{id}/retry — Retry / 重試

Only allowed when `status = FAILED`. Increments `retryCount`, re-publishes to MQ.

僅 `status = FAILED` 的通知可重試，`retryCount + 1`，重新送出 MQ。

**Response** `200 OK` — `NotificationResponse`  
**Error** `409 Conflict` — `INVALID_STATUS_TRANSITION` if not FAILED

---

### PATCH /api/notifications/{id}/tracking — Tracking / 追蹤

Records delivery or read timestamp. Typically called by an external webhook.

記錄送達或已讀時間，通常由外部 webhook 呼叫。

**Request**
```json
{ "event": "DELIVERED" }
```

`event` must be `DELIVERED` or `READ`.

**Response** `200 OK` — `NotificationResponse`

---

## Error Response Format / 錯誤回應格式

```json
{
  "errorCode": "NOTIFICATION_NOT_FOUND",
  "message": "Notification not found: 99",
  "details": null,
  "timestamp": "2026-06-25T10:00:00"
}
```

`details` is populated for validation errors with field-level messages.

驗證錯誤時 `details` 會帶有各欄位的錯誤說明。

| ErrorCode | HTTP | When |
|---|---|---|
| `NOTIFICATION_NOT_FOUND` | 404 | ID does not exist |
| `INVALID_STATUS_TRANSITION` | 409 | Retry on non-FAILED notification |
| `RATE_LIMIT_EXCEEDED` | 429 | Too many requests |
| `VALIDATION_ERROR` | 400 | Request body fails validation |
| `INVALID_ARGUMENT` | 400 | Bad argument (e.g. wrong HTTP method) |
| `INTERNAL_ERROR` | 500 | Unexpected server error |

---

## Cross-Cutting Features / 橫切功能

### Idempotency / 冪等性

Pass `X-Idempotency-Key: <uuid>` on `POST /api/notifications`. If the same key is seen again within 24 hours, the original response is returned without creating a new notification. Max length: 64 characters.

在 `POST /api/notifications` 帶 `X-Idempotency-Key` header。相同 key 在 24 小時內重送時，直接回傳原始結果，不重複建立通知。Key 最長 64 字元。

Uses Redis atomic `SET NX` to prevent race conditions on concurrent duplicate requests.

以 Redis `SET NX` 防止並發重複請求的 race condition。

### Rate Limiting / 速率限制

In-memory fixed-window rate limiting applied to all `/api/**` routes:

以 in-memory 固定視窗限制所有 `/api/**` 路由：

| Scope | Limit |
|---|---|
| Global | 200 req/sec across all clients |
| `POST /api/notifications` | 20 req/min per IP |
| All other endpoints | 100 req/min per IP |

### Scheduled Delivery / 排程發送

Set `scheduledAt` in the create request to defer delivery. A scheduler polls every 60 seconds for `SCHEDULED` notifications whose time has arrived and publishes them to MQ.

建立時帶 `scheduledAt` 可延後發送。Scheduler 每 60 秒掃描到期的 `SCHEDULED` 通知並送出 MQ。

A sweeper job runs every 120 seconds to recover notifications stuck in `PENDING` for more than 5 minutes (see implementation note under `POST /api/notifications`). Stuck rows are marked `FAILED` and become eligible for `POST /{id}/retry`.

另有一個 sweeper 每 120 秒執行，將卡在 `PENDING` 超過 5 分鐘的通知標為 `FAILED`，之後可透過 `POST /{id}/retry` 重試。

### Correlation ID / 請求追蹤

Every request receives an `X-Correlation-ID` response header. Pass the same header inbound to preserve the ID across service boundaries. All log lines for the same request include the correlation ID.

每個請求都會在 response 帶 `X-Correlation-ID`。可在 request 帶入同一 ID 串接跨服務追蹤。同一 request 的所有 log 行都帶有此 ID。

---

## Running Locally / 本地執行

### 1. Start infrastructure / 啟動基礎設施

```bash
docker-compose up -d
```

| Service | Port |
|---|---|
| MySQL | 3306 |
| Redis | 6379 |
| RocketMQ Namesrv | 9876 |
| RocketMQ Broker | 10911 |
| RocketMQ Console | 8088 |

### 2. Run the application / 啟動應用

```bash
./mvnw spring-boot:run
```

### 3. Run tests / 執行測試

```bash
./mvnw test
# Tests run: 81, Failures: 0, Errors: 0
```

---

## curl Examples / curl 範例

```bash
# Create email notification / 建立 Email 通知
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: req-001" \
  -d '{"type":"email","recipient":"user@example.com","subject":"Hello","content":"Test"}'

# Create scheduled notification / 建立排程通知
curl -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"sms","recipient":"+886912345678","content":"Your code is 1234","scheduledAt":"2026-07-01T09:00:00"}'

# Get by ID / 查詢單筆
curl http://localhost:8080/api/notifications/1

# Get recent 10 / 最近 10 筆
curl http://localhost:8080/api/notifications/recent

# Update / 更新
curl -X PUT http://localhost:8080/api/notifications/1 \
  -H "Content-Type: application/json" \
  -d '{"subject":"Updated subject","content":"New body"}'

# Delete / 刪除
curl -X DELETE http://localhost:8080/api/notifications/1

# Retry failed notification / 重試失敗通知
curl -X POST http://localhost:8080/api/notifications/1/retry

# Mark as delivered / 標記已送達
curl -X PATCH http://localhost:8080/api/notifications/1/tracking \
  -H "Content-Type: application/json" \
  -d '{"event":"DELIVERED"}'
```
