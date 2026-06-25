# Phase 2 — Design Document

Status: ✅ Confirmed by user

---

## API Contract

### 1. POST /notifications
**Request Body:**
```json
{
  "type": "email",
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Thanks for signing up!",
  "scheduledAt": "2025-07-16T09:00:00",
  "emailOptions": {
    "fromAddress": "no-reply@company.com",
    "replyTo": "support@company.com",
    "cc": ["cc@company.com"],
    "bcc": ["audit@company.com"],
    "contentType": "text/html",
    "attachmentUrls": ["https://cdn.example.com/file.pdf"]
  }
}
```
```json
{
  "type": "sms",
  "recipient": "+886912345678",
  "content": "Your OTP is 123456",
  "smsOptions": {
    "senderId": "MYAPP"
  }
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| type | String | YES | `"email"` 或 `"sms"`（`@Pattern`） |
| recipient | String | YES | email 格式（type=email）；E.164 電話（type=sms） |
| subject | String | NO | nullable；type=sms 且 null/blank 時填 `"無主旨"` |
| content | String | YES | not blank；type=sms 限 160 字元（純 ASCII）或 70 字元（含 Unicode） |
| scheduledAt | LocalDateTime | NO | null 代表立即發送；未來時間才合法 |
| emailOptions | EmailOptions | NO | 僅 type=email 有效 |
| smsOptions | SmsOptions | NO | 僅 type=sms 有效 |

**EmailOptions:**
| Field | Type | Required |
|-------|------|----------|
| fromAddress | String | NO（預設讀 config） |
| replyTo | String | NO |
| cc | List\<String\> | NO（每項驗 email 格式） |
| bcc | List\<String\> | NO（每項驗 email 格式） |
| contentType | String | NO（`"text/plain"` 或 `"text/html"`，預設 `"text/plain"`） |
| attachmentUrls | List\<String\> | NO（URL 格式） |

**SmsOptions:**
| Field | Type | Required |
|-------|------|----------|
| senderId | String | NO（max 11 chars，alphanumeric） |

**Headers:**
- `X-Idempotency-Key: <uuid>` — optional；相同 key 24h 內重複請求回原始結果

**Response 201:** full `NotificationResponse`（見下方）
**Response 400:** field-level validation errors
**Response 409:** idempotency key 衝突時不回 409，直接回原始 200 + header `X-Idempotency-Replayed: true`
**Response 429:** rate limit 超過（每個 recipient 每小時最多 10 則）

---

### 2. GET /notifications/{id}
**Response 200:** full `NotificationResponse`.
**Response 404:** `{"error": "Notification not found: {id}"}`

---

### 3. GET /notifications/recent
**Response 200:** List\<NotificationResponse\>，最多 10 筆，newest first.
Cache miss → MySQL 重建。

---

### 4. PUT /notifications/{id}
**Request Body:**
```json
{
  "subject": "Updated subject",
  "content": "Updated content"
}
```
**Response 200:** updated full `NotificationResponse`.
**Response 404:** `{"error": "Notification not found: {id}"}`

---

### 5. DELETE /notifications/{id}
Soft delete：設 `deleted_at = NOW()`，不真正刪除。
**Response 204:** No Content.
**Response 404:** `{"error": "Notification not found: {id}"}`（含已軟刪除的）

---

### 6. POST /notifications/{id}/retry
重試 status=FAILED 的通知，重新發送 RocketMQ。
**Response 200:** updated `NotificationResponse`（status 更新）
**Response 400:** `{"error": "Notification is not in FAILED status"}`
**Response 404:** 找不到

---

### 7. PATCH /notifications/{id}/tracking
供 provider webhook 回調，更新 `delivered_at` 或 `read_at`。
**Request Body:**
```json
{ "event": "DELIVERED" }
```
`event` 可為 `"DELIVERED"` 或 `"READ"`.
**Response 200:** updated `NotificationResponse`.
**Response 404:** 找不到

---

### NotificationResponse（所有通知 endpoint 共用）
```json
{
  "id": 1,
  "type": "email",
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Thanks for signing up!",
  "status": "SENT",
  "fromAddress": "no-reply@company.com",
  "replyTo": null,
  "senderId": null,
  "cc": [],
  "bcc": [],
  "contentType": "text/plain",
  "attachmentUrls": [],
  "retryCount": 0,
  "lastError": null,
  "scheduledAt": null,
  "sentAt": "2025-07-15T12:01:02",
  "deliveredAt": null,
  "readAt": null,
  "createdAt": "2025-07-15T12:01:02",
  "updatedAt": "2025-07-15T12:01:02"
}
```

---

## Data Model

### MySQL Table: `notifications`

```sql
CREATE TABLE notifications (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- Core
    type            VARCHAR(10)  NOT NULL,
    recipient       VARCHAR(255) NOT NULL,
    subject         VARCHAR(255) NULL,
    content         TEXT         NOT NULL,
    status          VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    -- Sender identity
    from_address    VARCHAR(255) NULL,
    reply_to        VARCHAR(255) NULL,
    sender_id       VARCHAR(50)  NULL,
    -- Extended (email only)
    cc              TEXT         NULL,        -- JSON array of email strings
    bcc             TEXT         NULL,        -- JSON array of email strings
    content_type    VARCHAR(20)  NOT NULL DEFAULT 'text/plain',
    attachments     TEXT         NULL,        -- JSON array of URL strings
    -- Retry
    retry_count     TINYINT      NOT NULL DEFAULT 0,
    last_error      VARCHAR(500) NULL,
    -- Scheduling
    scheduled_at    DATETIME     NULL,
    -- Tracking timestamps
    sent_at         DATETIME     NULL,
    delivered_at    DATETIME     NULL,
    read_at         DATETIME     NULL,
    -- Idempotency
    idempotency_key VARCHAR(64)  NULL,
    -- Soft delete
    deleted_at      DATETIME     NULL,
    -- Standard timestamps
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT chk_type         CHECK (type         IN ('email', 'sms')),
    CONSTRAINT chk_status       CHECK (status       IN ('PENDING', 'SENT', 'FAILED')),
    CONSTRAINT chk_content_type CHECK (content_type IN ('text/plain', 'text/html')),
    UNIQUE KEY uq_idempotency_key (idempotency_key)
);

CREATE INDEX idx_notifications_created_at ON notifications (created_at DESC);
CREATE INDEX idx_notifications_scheduled  ON notifications (scheduled_at, status, deleted_at);
CREATE INDEX idx_notifications_status     ON notifications (status, deleted_at);
```

`cc`, `bcc`, `attachments` 欄位儲存 JSON array string（`"[\"a@b.com\",\"c@d.com\"]"`），由 service 層 serialize/deserialize。避免 junction table 過度設計。

### MyBatis Mapper 說明
- `Notification` entity：純 POJO + Lombok，無 JPA 注解
- `cc`, `bcc`, `attachments` 使用 `JsonListTypeHandler`（`@MappedTypes(List.class)`）自動序列化
- 軟刪除：所有 SELECT 手動加 `AND deleted_at IS NULL`，不依賴 ORM 魔法
- `@Timed(value = "db.notification.execute", percentiles = {0.99, 0.95})` 在 Mapper 層埋 metrics
- `map-underscore-to-camel-case: true` 自動處理 snake_case ↔ camelCase

---

## Redis Design

| Key | Type | Value | Written by | Invalidated by |
|-----|------|-------|-----------|----------------|
| `notification:{id}` | String (JSON) | full `NotificationResponse` | Create/Get miss | Update, Delete, Retry |
| `notifications:recent` | List (JSON strings) | full responses, newest first | Create (`Lua: LPUSH+LTRIM 0 9`) | Update, Delete, Retry |
| `rate_limit:{recipient}:{yyyyMMddHH}` | String | count (INCR) | 每次 Create | TTL 自動過期（3600s） |
| `idempotency:{key}` | String | notification ID | Create | TTL 自動過期（86400s） |

Cache miss for `/recent` → 從 MySQL top-10 重建。
`notification:{id}` TTL = 60s（stale write-back self-heal）。

---

## RocketMQ Design

- **Topic:** `notification-topic`
- **Triggered by:** Create、Update、Retry
- **Message body (`NotificationEvent` record):**

```json
{
  "action": "CREATE",
  "id": 1,
  "type": "email",
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Thanks for signing up!",
  "fromAddress": "no-reply@company.com",
  "senderId": null,
  "occurredAt": "2025-07-15T12:01:02"
}
```
`action` ∈ `{ "CREATE", "UPDATE", "RETRY" }`

---

## Package Structure

```
src/main/java/com/example/demo/
├── controller/
│   └── NotificationController.java
├── service/
│   ├── NotificationService.java          (business logic + MQ)
│   └── NotificationCacheService.java     (Redis 封裝，所有 Redis 操作集中)
├── repository/
│   └── NotificationRepository.java
├── domain/
│   └── Notification.java
├── dto/
│   ├── NotificationRequest.java
│   ├── EmailOptions.java                 (record，nested in request)
│   ├── SmsOptions.java                   (record，nested in request)
│   ├── UpdateNotificationRequest.java
│   ├── TrackingUpdateRequest.java
│   ├── NotificationResponse.java
│   └── CreateResult.java
├── event/
│   └── NotificationEvent.java
├── validator/
│   ├── ValidNotificationRequest.java
│   └── NotificationRequestValidator.java
├── scheduler/
│   └── NotificationScheduler.java        (@Scheduled，每分鐘掃描 scheduled_at)
├── config/
│   └── RedisConfig.java
└── common/
    ├── exception/
    │   ├── GlobalExceptionHandler.java
    │   ├── NotificationNotFoundException.java
    │   └── RateLimitExceededException.java
    └── converter/
        └── JsonListConverter.java
```

---

## Validator Cross-Field Rules (`NotificationRequestValidator`)

| Rule | Condition |
|------|-----------|
| recipient 為 email 格式 | type = email |
| recipient 為 E.164（`^\+[1-9]\d{7,14}$`） | type = sms |
| content ≤ 160（純 ASCII）或 ≤ 70（含 Unicode chars） | type = sms |
| emailOptions 若提供，cc/bcc 每項驗 email 格式 | type = email |
| contentType 只能 text/plain | type = sms（sms 不支援 html） |
| scheduledAt 必須是未來時間 | scheduledAt 不為 null |

---

## Service Flows

### Create Flow
```
1.  check idempotency key (Redis) → 若存在，回原始結果
2.  check rate limit (Redis INCR) → 若超過，throw RateLimitExceededException
3.  build Notification entity (status=PENDING)
4.  若 scheduledAt != null → save，直接回 201（等 scheduler 發送）
5.  repository.save(notification)      ← first DB write
6.  try rocketMQTemplate.syncSend()
    ├─ 成功 → status="SENT", sentAt=now()
    └─ 失敗 → status="FAILED", lastError=message
7.  repository.save(status/sentAt)     ← second DB write
8.  cacheService.cacheNotification()
9.  cacheService.pushToRecent()
10. store idempotency key in Redis (TTL 24h)
11. return NotificationResponse
```

### Scheduler Flow（`@Scheduled(fixedDelay = 60000)`）
```
1. 查詢：scheduled_at <= NOW() AND status='PENDING' AND retry_count < 3 AND deleted_at IS NULL
2. 對每筆通知：
   a. try rocketMQTemplate.syncSend()
      ├─ 成功 → status='SENT', sentAt=now(), 更新 Redis cache
      └─ 失敗 → retry_count++
                if retry_count >= 3 → status='FAILED'（終止自動重試）
                else                → 保持 status='PENDING'（下一輪再試）
                last_error = message
   b. repository.save()
```
最多自動重試 3 次（間隔 1 分鐘）。超過後 status=FAILED，需人工 POST /notifications/{id}/retry。
即時通知（scheduledAt=null）失敗不自動重試，只能人工 retry。

### Retry Flow
```
1. 找通知，確認 status='FAILED'
2. try rocketMQTemplate.syncSend()
   ├─ 成功 → status="SENT", sentAt=now(), retryCount++
   └─ 失敗 → status="FAILED", retryCount++, lastError=message
3. repository.save()
4. cacheService.evictNotification(id) + evictRecent()
5. return NotificationResponse
```

---

## Dependencies

| Change | Reason |
|--------|--------|
| ADD `spring-boot-starter-web` | REST controllers |
| ADD `mybatis-spring-boot-starter:3.0.4` | MyBatis data access |
| ADD `spring-boot-starter-actuator` | Micrometer @Timed metrics |
| ADD `spring-boot-starter-validation` | Bean Validation |
| ADD `com.mysql:mysql-connector-j` | MySQL driver |
| REPLACE `rocketmq-client 5.3.2` → `rocketmq-spring-boot-starter 2.3.1` | RocketMQTemplate |
| ADD `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | LocalDateTime in JSON |
| KEEP `spring-boot-starter-data-redis` | Redis |

---

## Race Conditions & Performance

### Race 1 — Concurrent LPUSH + LTRIM
**Mitigation:** Lua script（原子執行）
```lua
redis.call('LPUSH', KEYS[1], ARGV[1])
redis.call('LTRIM', KEYS[1], 0, 9)
```

### Race 2 — Read-Write Cache Stale Write-Back
**Mitigation:** `notification:{id}` TTL = 60s，自動 self-heal

### Race 3 — Cache Stampede on `/recent`
**Accepted tradeoff**（homework volume 低）；production 用 SETNX lock

### Race 4 — Rate Limit Counter Atomicity
**Mitigation:** `INCR` 是 Redis 原子操作；初次建立時用 `SET ... NX EX 3600` 確保 TTL 正確設定

### Race 5 — Idempotency Key Race（double submit）
**Mitigation:** Redis `SET idempotency:{key} {id} NX EX 86400`，NX 保證只有第一個請求能設值

### Performance
- `idx_notifications_created_at`：`ORDER BY created_at DESC LIMIT 10` → O(log n)
- `idx_notifications_scheduled`：scheduler 查詢 covering index
- `idx_notifications_status`：retry / monitoring 查詢

---

## README Bonus Checklist

| Bonus 要求 | 實作方式 | 狀態 |
|-----------|---------|------|
| Spring Cache / RedisTemplate encapsulation | `NotificationCacheService` 封裝所有 `StringRedisTemplate` 操作 | ✅ |
| Take race conditions into account | Lua LPUSH+LTRIM, 60s TTL, INCR 原子, SET NX for idempotency | ✅ |
| Proper error handling with meaningful status codes | `GlobalExceptionHandler` → 400 / 404 / 204 / 429 / 500 | ✅ |
| Own DTO and RocketMQ message format | `NotificationEvent` record with action field | ✅ |
| Consistent and modular code structure | package-by-feature，分離 notification / scheduler / common | ✅ |
| Test case coverage | Phase 5：service + controller tests，正反例全覆蓋 | ⏳ Phase 5 |

---

## Real-World Considerations — All Implemented

| 功能 | 實作方式 |
|------|---------|
| Sender identity | `from_address`、`reply_to`（email）、`sender_id`（sms）欄位 |
| 重試機制 | `POST /notifications/{id}/retry`；`retry_count`、`last_error` 欄位 |
| Soft delete | `deleted_at` 欄位 + `@Where(clause = "deleted_at IS NULL")` |
| Scheduling | `scheduled_at` 欄位 + `@Scheduled` 每分鐘掃描 |
| Idempotency key | `X-Idempotency-Key` header + Redis `SET NX EX 86400` |
| Rate limiting | Redis `INCR rate_limit:{recipient}:{hour}`，超過 10 回 429 |
| HTML email | `content_type` 欄位（`text/plain` / `text/html`）；SMS 只允許 plain |
| Delivered/Read tracking | `sent_at`、`delivered_at`、`read_at` 欄位；`PATCH /notifications/{id}/tracking` webhook endpoint |
| CC / BCC | `cc`、`bcc` JSON array 欄位；每項驗 email 格式 |
| Attachments | `attachments` JSON array of URLs；無 file upload，假設已外部托管 |
| Unicode SMS 分片 | content 含非 GSM-7 字元時上限降為 70 字元（validator 自動判斷） |
| Template 系統 | 不實作 |
