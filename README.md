
# 📬 Notification Service

A RESTful notification service built with Spring Boot 3.5, supporting email and SMS delivery via RocketMQ, with Redis caching and MySQL persistence.

## 🚀 Quick Start

**Prerequisites:** Java 21, Docker

```bash
# 1. Start infrastructure
docker-compose up -d

# 2. Run the application
./mvnw spring-boot:run

# 3. Send a test notification
curl -si -X POST http://localhost:8080/api/notifications \
  -H "Content-Type: application/json" \
  -d '{"type":"email","recipient":"user@example.com","content":"Hello!"}'
```

Expected response: `HTTP/1.1 201`, `"status": "SENT"`.

> See [`HELP.md`](HELP.md) if you run into setup issues (RocketMQ broker config, DB schema).

---

## 📖 How to Read This Project

| Document | Content |
|---|---|
| [`IMPLEMENTATION.md`](IMPLEMENTATION.md) | API reference, architecture overview, curl examples, how to run |
| [`HELP.md`](HELP.md) | Setup scripts — `init.sql` (DB schema) and `broker.conf` (RocketMQ) |
| [`docs/phase1-feature-spec.md`](docs/phase1-feature-spec.md) | Feature spec — requirements breakdown, user stories, acceptance criteria |
| [`docs/phase2-design.md`](docs/phase2-design.md) | Design — API contract, data model, package structure, key decisions |
| [`docs/phase3-implementation.md`](docs/phase3-implementation.md) | Implementation notes — layers, design decisions, cache protection |
| [`docs/phase4-code-review.md`](docs/phase4-code-review.md) | Self code review — issues found and resolved |
| [`docs/phase5-tests.md`](docs/phase5-tests.md) | Test coverage — 81 tests across service, controller, validator, Redis util, scheduler |
| [`docs/manual-testing.md`](docs/manual-testing.md) | Postman manual testing flows — 8 scenarios covering happy path, idempotency, scheduling, tracking, retry, validation |

> **Development process:** This project was built collaboratively between the developer and [Claude Code](https://claude.com/claude-code). The developer drove all key decisions — requirements scoping, design trade-offs, feature priorities, and review direction — while Claude assisted with implementation, code generation, and documentation. The workflow followed a structured spec → design → implement → review → test cycle. Specialised agents in [`.claude/agents/`](.claude/agents/) (code-reviewer, security-engineer) were used for independent code and security reviews. The Claude skills used are in [`.claude/skills/`](.claude/skills/) — covering Spring Boot patterns, coding conventions, transaction handling, web infra, and more.

---

# 📬 Notification Service – Backend Homework

This is a technical assignment for backend engineer candidates. You are expected to build a RESTful notification service using **Spring Boot**, integrating **MySQL**, **Redis**, and **RocketMQ**.

---

## 🎯 Objective

Design and implement a notification API system that allows users to send either **email** or **SMS** notifications. The system must:

- Persist notifications in a **MySQL database**
- Push messages to **RocketMQ**
- Cache and retrieve recent notifications using **Redis**

---

## 🧰 Tech Requirements

You **must use** the following technologies:

- **Java 21+**
- **Spring Boot**
- **MySQL** (for persistence)
- **Redis** (for caching)
- **RocketMQ** (for event messaging)

You may use starter dependencies such as:
- Spring Web
- Spring Data JPA
- Spring Cache
- RocketMQ Spring Boot Starter

---

## 🔧 Features to Implement

### 1️⃣ Create Notification

**Endpoint**  
`POST /notifications`

**Request JSON**
```json
{
  "type": "email",              // or "sms"
  "recipient": "user@example.com",
  "subject": "Welcome!",
  "content": "Thanks for signing up!"
}
```
Behavior:  
- Save the notification to MySQL  
- Send the message to RocketMQ topic notification-topic  
- Add it to Redis cache of the 10 most recent notifications  

⸻

### 2️⃣ Get Notification by ID

**Endpoint**  
`GET /notifications/{id}`

Behavior:
- Retrieve the notification from Redis if available
- Otherwise fetch it from MySQL and update the Redis cache

⸻

### 3️⃣ Get Recent Notifications

**Endpoint**  
`GET /notifications/recent`

Response Example
```json
[
  {
    "id": 123,
    "type": "email",
    "recipient": "user@example.com",
    "subject": "Welcome!",
    "createdAt": "2025-07-15T12:01:02Z"
  }
]
```

⸻
### 4️⃣ Update Notification

**Endpoint**  
`PUT /notifications/{id}`

**Request Payload**
```json
{
  "subject": "Updated subject line",
  "content": "Updated content of the notification"
}
```

Behavior:
- Update the subject and content fields of an existing notification in MySQL
- If the notification exists in Redis, either:
  - Update the cached value, or
  - Invalidate the cache entry
- If the notification does not exist (in DB), return 404 Not Found  


⸻

### 5️⃣ Delete Notification

**Endpoint**
`DELETE /notifications/{id}`

Behavior:
- Delete the notification with the specified ID from MySQL
- Remove it from Redis cache if present
- Return 204 No Content on success
- If the notification does not exist, return 404 Not Found


⸻

🧪 Bonus (Optional)  
- Use Spring Cache abstraction or RedisTemplate encapsulation
- Take race conditions into account
- Apply proper error handling with meaningful status codes
- Define your own DTO and message format for RocketMQ
- Use consistent and modular code structure (controller, service, repository, config, etc.)
- Test case coverage: as much as possible

⸻

🐳 Environment Setup

Use the provided docker-compose.yaml file to start required services:

Service	Port  
MySQL	3306  
Redis	6379  
RocketMQ Namesrv	9876  
RocketMQ Broker	10911  
RocketMQ Console	8088  

To start the services:

```commandline
docker-compose up -d
```

MySQL credentials:
- User: taskuser
- Password: taskpass
- Database: taskdb

You may edit init.sql to create required tables automatically.

⸻

🚀 Getting Started

To run the application:

./mvnw spring-boot:run

Make sure to update your application.yml with the proper connections for:
- spring.datasource.url
- spring.redis.host
- rocketmq.name-server

⸻

📤 Submission

Please submit a `public Github repository` that includes:
- ✅ Complete and executable source code
- ✅ README.md (this file)
- ✅ Any necessary setup or data scripts please add them in HELP.md
- ✅ Optional: Postman collection or curl samples  

⸻

📌 Notes
- Focus on API correctness, basic error handling, and proper use of each technology
- You may use tools like Vibe Coding / ChatGPT to assist, but please write and understand your own code
- The expected time to complete is around 3 hours

Good luck!

---
