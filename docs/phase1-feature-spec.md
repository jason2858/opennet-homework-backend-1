# Phase 1 — Feature Spec (Confirmed)

Status: ✅ Confirmed by user

---

## Summary

Build a RESTful Notification Service for sending email or SMS notifications.
Each notification is persisted in MySQL, published to RocketMQ (`notification-topic`),
and cached in Redis (10 most recent + individual lookup by ID).
The service supports full CRUD with cache coherence between MySQL and Redis.

---

## Scope

**IN scope:**
- 5 REST endpoints: Create, Get by ID, Get Recent, Update, Delete
- MySQL persistence via Spring Data JPA
- Redis caching: individual by ID + top-10 recent list (full objects including content)
- RocketMQ event publishing on Create AND Update
- Input validation and error handling (400, 404, 204)

**OUT of scope:**
- Authentication / authorization
- Actual email/SMS delivery
- RocketMQ consumer / downstream message processing
- Pagination beyond top-10 recent list

---

## User Stories

1. As a client, I want to **create a notification** so it is persisted, an event is published, and it appears in the recent cache.
2. As a client, I want to **retrieve a notification by ID** with fast response from Redis cache when available.
3. As a client, I want to **list the 10 most recent notifications** (full objects, newest first) from Redis.
4. As a client, I want to **update a notification's subject and content** with the change reflected in cache and published to RocketMQ.
5. As a client, I want to **delete a notification** fully removed from MySQL and Redis.

---

## Acceptance Criteria

1. `POST /notifications` valid → 201, saved in MySQL, event on RocketMQ, Redis recent list updated (max 10).
2. `POST /notifications` missing `type` or `recipient` or `content` → 400 with field-level errors.
3. `POST /notifications` with `type` not in `["email","sms"]` → 400.
4. `GET /notifications/{id}` → 200; cache miss fetches from MySQL and populates Redis; cache hit served from Redis.
5. `GET /notifications/{id}` non-existent → 404.
6. `GET /notifications/recent` → 200, up to 10 full notification objects newest-first from Redis; if cache empty, rebuilt from MySQL.
7. `PUT /notifications/{id}` valid → 200, MySQL updated, RocketMQ event published, Redis individual cache invalidated, recent list invalidated.
8. `PUT /notifications/{id}` non-existent → 404.
9. `DELETE /notifications/{id}` → 204, removed from MySQL and Redis.
10. `DELETE /notifications/{id}` non-existent → 404.

---

## Decisions Made

| Question | Decision |
|----------|----------|
| `subject` for SMS | Optional (nullable). Service defaults null/blank to `"無主旨"` before saving. |
| Recent list ordering | `createdAt` DESC (newest first). |
| Update → RocketMQ? | YES — publishes UPDATE event for downstream data consistency. |
| Redis stores content? | YES — full `NotificationResponse` (including `content`) stored in Redis. `/recent` also returns `content`. |
