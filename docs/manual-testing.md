# Manual Testing (curl)

Base URL: `http://localhost:8080`

Make sure `docker-compose up -d` and `./mvnw spring-boot:run` are both running before starting.

> **Tip:** All commands use `-si` — the first line of output shows the HTTP status (`HTTP/1.1 201`), followed by headers, then the response body. If you prefer pretty-printed JSON and don't need the status code, replace `-si` with `-s` and add `| jq .`.

---

## Flow 1 — Create Notification 各種欄位組合

### 1a — Email 最簡（只填必填）

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "Thanks for signing up!"
  }'
```

Expected: `HTTP/1.1 201`, `status: SENT`, `subject: null`, `fromAddress: no-reply@example.com`（預設值）。

---

### 1b — Email 完整欄位

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "subject": "Your order is confirmed",
    "content": "<h1>Order #1234</h1><p>Thank you!</p>",
    "emailOptions": {
      "fromAddress": "orders@myshop.com",
      "replyTo": "support@myshop.com",
      "cc": ["manager@myshop.com"],
      "bcc": ["audit@myshop.com"],
      "contentType": "text/html",
      "attachmentUrls": ["https://example.com/invoice.pdf"]
    }
  }'
```

Expected: `HTTP/1.1 201`，response body 帶有 `cc`、`bcc`、`contentType: text/html`。

---

### 1c — SMS 最簡

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "sms",
    "recipient": "+886912345678",
    "content": "Your verification code is 123456"
  }'
```

Expected: `HTTP/1.1 201`, `status: SENT`。

---

### 1d — SMS 帶 senderId

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "sms",
    "recipient": "+886987654321",
    "content": "Flash sale starts now! Use code SAVE20",
    "smsOptions": {
      "senderId": "MyShop"
    }
  }'
```

Expected: `HTTP/1.1 201`，response body 帶有 `senderId: MyShop`。

---

### Get by ID（驗證 cache）

```bash
curl -si http://localhost:8080/api/notifications/1
```

送兩次，第二次從 Redis cache 回傳（查 app log 確認沒有 SQL 查詢）。

---

### Get recent list

```bash
curl -si http://localhost:8080/api/notifications/recent
```

Expected: `HTTP/1.1 200`，最近 10 筆，包含剛才建立的通知。

---

## Flow 2 — Idempotency

**Goal:** 同一個 key 送兩次，第二次不建立新通知，回傳原始結果。

### Step 1 — 第一次請求（建立通知）

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-idem-001" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "Idempotency test"
  }'
```

Expected: `HTTP/1.1 201`，response headers 沒有 `X-Idempotency-Replayed`。

### Step 2 — 重送相同請求

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: test-idem-001" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "Idempotency test"
  }'
```

Expected: `HTTP/1.1 200`，response headers 有 `X-Idempotency-Replayed: true`，`id` 與 Step 1 相同，DB 沒有新增資料。

---

## Flow 3 — Scheduled Notification

**Goal:** 建立排程通知，確認等待中，scheduler 觸發後狀態變 SENT。

### Step 1 — 建立排程通知

> 將 `scheduledAt` 修改為 1–2 分鐘後的時間再執行。

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "Scheduled delivery test",
    "scheduledAt": "2026-06-25T20:05:00"
  }'
```

Expected: `HTTP/1.1 201`, `status: SCHEDULED`, `sentAt: null`。

### Step 2 — 等待中查詢（替換 {id}）

```bash
curl -si http://localhost:8080/api/notifications/{id}
```

Expected: `status: SCHEDULED`（scheduler 每 60 秒執行一次）。

### Step 3 — Scheduler 觸發後再查詢

```bash
curl -si http://localhost:8080/api/notifications/{id}
```

Expected: `status: SENT`，`sentAt` 有值。

---

## Flow 4 — Update & Delete

### Step 1 — 建立通知（記下回傳的 id）

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "Original content"
  }'
```

### Step 2 — 更新（替換 {id}）

```bash
curl -si -X PUT http://localhost:8080/api/notifications/{id} \
  -H "Content-Type: application/json" \
  -d '{
    "subject": "Updated subject",
    "content": "Updated content"
  }'
```

Expected: `HTTP/1.1 200`，response body 顯示新的 subject 和 content。

### Step 3 — 確認 cache 已清除

```bash
curl -si http://localhost:8080/api/notifications/{id}
```

Expected: 回傳更新後的內容（update 時 cache 已 evict，重新從 DB 讀取）。

### Step 4 — 刪除

```bash
curl -si -X DELETE http://localhost:8080/api/notifications/{id}
```

Expected: `HTTP/1.1 204`，無 response body。

### Step 5 — 確認已刪除

```bash
curl -si http://localhost:8080/api/notifications/{id}
```

Expected: `HTTP/1.1 404`, `errorCode: NOTIFICATION_NOT_FOUND`。

---

## Flow 5 — Delivery Tracking

**Goal:** 模擬 webhook 更新狀態 SENT → DELIVERED → READ。

### Step 1 — 建立通知（記下 id）

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "Tracking test"
  }'
```

### Step 2 — 標記 DELIVERED

```bash
curl -si -X PATCH http://localhost:8080/api/notifications/{id}/tracking \
  -H "Content-Type: application/json" \
  -d '{"event": "DELIVERED"}'
```

Expected: `HTTP/1.1 200`，`deliveredAt` 有值。

### Step 3 — 標記 READ

```bash
curl -si -X PATCH http://localhost:8080/api/notifications/{id}/tracking \
  -H "Content-Type: application/json" \
  -d '{"event": "READ"}'
```

Expected: `HTTP/1.1 200`，`readAt` 有值。

---

## Flow 6 — Retry 失敗通知

**Goal:** 製造 FAILED 狀態後重試。

### Step 1 — 停掉 RocketMQ broker

```bash
docker stop rocketmq-broker
```

### Step 2 — 建立通知（MQ 發送失敗）

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{
    "type": "email",
    "recipient": "user@example.com",
    "content": "This will fail"
  }'
```

Expected: `HTTP/1.1 201`, `status: FAILED`, `lastError` 有值。

### Step 3 — 重啟 broker

```bash
docker start rocketmq-broker
```

### Step 4 — Retry（替換 {id}）

```bash
curl -si -X POST http://localhost:8080/api/notifications/{id}/retry
```

Expected: `HTTP/1.1 200`, `status: SENT`, `retryCount: 1`。

### Step 5 — 對已成功的通知再 retry（應失敗）

```bash
curl -si -X POST http://localhost:8080/api/notifications/{id}/retry
```

Expected: `HTTP/1.1 409`, `errorCode: INVALID_STATUS_TRANSITION`。

---

## Flow 7 — Validation Errors

### 缺少必填欄位

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type": "email", "recipient": "user@example.com"}'
```

Expected: `HTTP/1.1 400`, `errorCode: VALIDATION_ERROR`, `details` 包含 content 欄位錯誤。

---

### 無效的 type

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type": "fax", "recipient": "user@example.com", "content": "test"}'
```

Expected: `HTTP/1.1 400`, `errorCode: VALIDATION_ERROR`。

---

### 無效的 tracking event

```bash
curl -si -X PATCH http://localhost:8080/api/notifications/1/tracking \
  -H "Content-Type: application/json" \
  -d '{"event": "OPENED"}'
```

Expected: `HTTP/1.1 400`, `errorCode: VALIDATION_ERROR`。

---

### X-Idempotency-Key 超過 64 字元

```bash
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -H "X-Idempotency-Key: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" \
  -d '{"type": "email", "recipient": "user@example.com", "content": "test"}'
```

Expected: `HTTP/1.1 400`。

---

### 查詢不存在的 ID

```bash
curl -si http://localhost:8080/api/notifications/99999
```

Expected: `HTTP/1.1 404`, `errorCode: NOTIFICATION_NOT_FOUND`。

---

## Correlation ID

每個 response 都有 `X-Correlation-ID` header。帶入 request 可以串接跨服務追蹤：

```bash
curl -si http://localhost:8080/api/notifications/1 \
  -H "X-Correlation-ID: my-trace-id-001"
```

查看 app log — 這個 request 的所有 log 行都會帶有 `my-trace-id-001`。
