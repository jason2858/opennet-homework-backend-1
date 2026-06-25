---
name: coding-conventions
related-skills:
  - web-infra       # exception hierarchy, GlobalExceptionHandler, response helpers, interceptors
  - transaction-patterns  # @Transactional rules for multi-step service operations
description: Project-specific Spring Boot coding conventions — MyBatis mapper pattern, service layer separation (CoreService/Service), package structure, constants, enums, DTOs, Redis scripts, Lombok usage. Load during implementation phase of any feature in this project.
metadata:
  version: "1.0.0"
  domain: backend
  triggers: mapper, service layer, package structure, constants, dto, enum, lombok, coding pattern, 程式慣例, 架構慣例
  role: reference
  scope: implementation
---

# Coding Conventions

Project-specific conventions for this Spring Boot + MyBatis project.
These override generic `spring-boot/` patterns where they conflict (e.g., MyBatis replaces Spring Data JPA).

---

## Package Structure

```
src/main/java/com/example/demo/
├── controller/
│   └── {Entity}Controller.java
├── service/
│   ├── {Entity}Service.java          ← business logic
│   └── core/
│       └── {Entity}CoreService.java  ← DB access wrapper
├── repository/
│   └── {Entity}Mapper.java           ← MyBatis SQL
├── model/
│   ├── entity/
│   │   └── {Entity}.java             ← pure POJO (no JPA annotations)
│   ├── dto/
│   │   ├── request/
│   │   │   └── {Entity}Request.java
│   │   ├── response/
│   │   │   └── {Entity}Response.java
│   │   └── {Shared}Dto.java          ← shared between request/response (Dto suffix)
│   ├── enums/
│   │   └── {Entity}Status.java
│   └── mq/
│       └── {Entity}Event.java
├── util/
│   ├── {Entity}RedisUtil.java        ← Redis cache operations
│   └── {Entity}RedisScripts.java     ← Lua scripts
├── constants/
│   ├── MetricsConstants.java
│   ├── TimeConstants.java
│   └── ErrorMessage.java
├── common/
│   ├── converter/
│   │   └── {Entity}Converter.java    ← static Entity→DTO converters (no state, final class)
│   ├── exception/
│   │   ├── ErrorCode.java
│   │   ├── ErrorResponse.java
│   │   ├── ApiException.java
│   │   ├── GlobalExceptionHandler.java
│   │   └── {Entity}NotFoundException.java
│   ├── filter/
│   │   └── CorrelationIdFilter.java  ← @Component, auto-registered, highest precedence
│   ├── handler/
│   │   └── {Feature}ResponseHandler.java
│   ├── interceptor/
│   │   └── {Name}Interceptor.java    ← HandlerInterceptor impls, registered in WebConfig
│   └── typehandler/
│       └── JsonListTypeHandler.java  ← MyBatis List<String>↔JSON column handler
├── config/
│   ├── MetricsConfig.java            ← TimedAspect bean
│   └── WebConfig.java                ← registers interceptors via WebMvcConfigurer
├── scheduler/
│   └── {Entity}Scheduler.java
└── validator/
    └── Valid{Constraint}.java
```

---

## 1. Entity — Pure POJO

Annotations: `@Data @Builder @NoArgsConstructor @AllArgsConstructor`. **No JPA annotations.**

Rules:
- Use primitive `int` for counters that are always set (e.g., `retryCount`)
- `List<String>` for JSON columns — requires `JsonListTypeHandler` (Section 10)
- `@Data` is safe here: no bidirectional JPA relationships exist

---

## 2. Data Access — MyBatis `@Mapper`

**Never use Spring Data JPA.** All data access goes through MyBatis `@Mapper`.

```java
@Mapper
@Timed(value = MetricsConstants.DB_EXECUTE, percentiles = {0.99, 0.95})
public interface NotificationMapper {

    @Insert("INSERT INTO notifications (type, recipient, subject, content, status, " +
            "from_address, idempotency_key) " +
            "VALUES (#{type}, #{recipient}, #{subject}, #{content}, #{status}, " +
            "#{fromAddress}, #{idempotencyKey})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Notification notification);

    @Update("UPDATE notifications SET status = #{status}, sent_at = #{sentAt}, " +
            "deleted_at = #{deletedAt} WHERE id = #{id} AND deleted_at IS NULL")
    int update(Notification notification);

    @Select("SELECT * FROM notifications WHERE id = #{id} AND deleted_at IS NULL")
    @Results(id = "notificationMap", value = {
        @Result(property = "cc", column = "cc", typeHandler = JsonListTypeHandler.class),
        @Result(property = "bcc", column = "bcc", typeHandler = JsonListTypeHandler.class),
        @Result(property = "attachments", column = "attachments", typeHandler = JsonListTypeHandler.class)
    })
    Optional<Notification> findById(Long id);

    @Select("SELECT * FROM notifications WHERE deleted_at IS NULL " +
            "ORDER BY created_at DESC LIMIT #{limit}")
    @ResultMap("notificationMap")
    List<Notification> findRecentByLimit(@Param("limit") int limit);
}
```

**Rules:**
- `@Timed` on every `@Mapper` interface — no exceptions
- `@Results(id = "...")` on the first SELECT, `@ResultMap("...")` on all subsequent SELECTs in same interface
- Soft delete: `AND deleted_at IS NULL` in every SELECT and UPDATE WHERE clause
- INSERT: exclude `id`, `created_at`, `updated_at` — DB auto-generates these
- UPDATE: single explicit call after all mutations — never "save and re-save"
- Mapper parameters must be **open/generic** (e.g., `@Param("limit") int limit`, not hardcoded)
- `List<String>` columns require `JsonListTypeHandler`

**Required `application.yaml`:**
```yaml
mybatis:
  type-handlers-package: com.example.demo.common.typehandler
  configuration:
    map-underscore-to-camel-case: true
```

**Required `MetricsConfig`:** Register `TimedAspect` bean with `MeterRegistry`. Requires `spring-boot-starter-aop` dependency.

---

## 3. Service Layer Separation

Split into two classes. **`{Entity}Service` must not depend on `{Entity}Mapper` directly.**

**Responsibility table:**

| Layer | Responsibility |
|---|---|
| `{Entity}Mapper` | SQL only — open/generic params |
| `{Entity}CoreService` | DB access + null safety + param defaults |
| `{Entity}Service` | Business logic, caching, MQ publishing |
| `{Entity}RedisUtil` | Redis operations (`@Component`, not `@Service`) |
| `{Entity}Converter` | Static Entity → DTO (final class, `common/converter/`) |

`{Entity}CoreService` wraps mapper calls, provides `findByIdOrThrow()`, centralises defaults like `DEFAULT_RECENT_LIMIT`. No business logic.

`{Entity}Service` calls CoreService for DB and RedisUtil for cache. Never injects `{Entity}Mapper` directly.

---

## 4. Lombok Usage

| Class type | Annotations |
|---|---|
| Entity POJO | `@Data @Builder @NoArgsConstructor @AllArgsConstructor` |
| Service / Controller / Scheduler | `@RequiredArgsConstructor` |
| DTOs | Java records — **no Lombok needed** |
| Enums with fields | `@Getter @RequiredArgsConstructor` |

**`@Value` fields are NOT `final`** — Spring injects them after the constructor:
```java
@Value("${notification.default-from-address:no-reply@example.com}")
private String defaultFromAddress;   // ✅ NOT final
```

Requires `lombok` in `annotationProcessorPaths` of `maven-compiler-plugin` in `pom.xml`.

---

## 4a. Jackson / Serialization Config — Prefer yaml Over Beans

Spring Boot 3.x auto-configures `JavaTimeModule`. Do **not** create a custom `ObjectMapper` bean just for serialization settings.

```yaml
spring:
  jackson:
    serialization:
      write-dates-as-timestamps: false
    deserialization:
      fail-on-unknown-properties: false
    default-property-inclusion: non_null
```

**Exception:** Multiple `ObjectMapper` instances with different formats → use a specific bean name (e.g., `avroObjectMapper`).

---

## 5. DTOs — Java Records

| Location | Contents | Naming |
|---|---|---|
| `dto/request/` | Inbound API request bodies | `{Entity}Request`, `Update{Entity}Request` |
| `dto/response/` | Outbound API responses | `{Entity}Response` |
| `dto/` | Shared between layers | `{Name}Dto` suffix always |

```java
public record NotificationRequest(
    @NotBlank(message = "type is required")
    @Pattern(regexp = "^(email|sms)$", message = "type must be 'email' or 'sms'")
    String type,

    @NotBlank(message = "recipient is required")
    String recipient,

    String subject,

    @NotBlank(message = "content is required")
    String content,

    LocalDateTime scheduledAt,
    @Valid EmailOptionsDto emailOptions,
    @Valid SmsOptionsDto smsOptions
) {}
```

Use `@Valid` on nested DTO fields to trigger cascade validation.

---

## 6. Constants — One File Per Concern

**Never** create a god-class `Constants.java`. Split by concern into `public final class` with private constructor.

```java
// constants/MetricsConstants.java
public final class MetricsConstants {
    public static final String DB_EXECUTE = "db.notification.execute";
    private MetricsConstants() {}
}
```

Other files follow the same pattern:
- `TimeConstants` — ZoneId, DateTimeFormatter
- `MqConstants` — MQ topic names
- `ErrorMessage` — user-facing error message strings

No magic strings in business code — every literal belongs in a constants file.

---

## 7. Enums — `model/enums/` Package

```java
public enum NotificationStatus { PENDING, SCHEDULED, SENT, FAILED }
public enum NotificationType { EMAIL, SMS }
public enum TrackingEvent { DELIVERED, READ }
public enum NotificationAction { CREATE, UPDATE, RETRY }
```

**Rule:** Never use raw strings like `"PENDING"` or `"email"` in business logic. For case-insensitive type checks (API sends lowercase `"email"`):
```java
// ✅
NotificationType.EMAIL.name().equalsIgnoreCase(req.type())
// ❌
"email".equals(req.type())
```

---

## 8. Redis Scripts — `util/{Entity}RedisScripts.java`

Scripts are `public static final DefaultRedisScript<Long>` using text blocks.

```java
public final class NotificationRedisScripts {

    public static final DefaultRedisScript<Long> PUSH_RECENT = new DefaultRedisScript<>("""
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, 9)
            return 1
            """, Long.class);

    private NotificationRedisScripts() {}
}
```

Rules: scripts in `util/`, not `constants/`; one file per entity; return type `Long.class` unless a string result is needed.

---

## 9. Redis Utility — `util/{Entity}RedisUtil`

`@Component` (NOT `@Service`) — cache utilities are infrastructure helpers, not business logic.

Key patterns for idempotency (two-phase: claim → resolve):

```java
// Phase 1: atomically claim slot with SET NX before processing
public boolean tryClaimIdempotencyKey(String key) {
    return Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(
            IDEMPOTENCY_PREFIX + key, IDEMPOTENCY_PENDING,
            Duration.ofSeconds(IDEMPOTENCY_CLAIM_TTL_SECONDS)
        )
    );
}

// Skip PENDING placeholder — only return resolved IDs
public Optional<Long> getIdempotencyResult(String key) {
    String value = redis.opsForValue().get(IDEMPOTENCY_PREFIX + key);
    if (value == null || IDEMPOTENCY_PENDING.equals(value)) return Optional.empty();
    return Optional.of(Long.parseLong(value));
}

// Phase 2: replace PENDING with real notification ID (24h TTL)
public void resolveIdempotencyKey(String key, Long notificationId) {
    redis.opsForValue().set(
        IDEMPOTENCY_PREFIX + key, String.valueOf(notificationId),
        IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS
    );
}
```

For infrastructure rate limiting (IP-based), use `RateLimitInterceptor` — not RedisUtil. See `web-infra` skill.

---

## 10. JsonListTypeHandler

Required for any `List<String>` field mapped to a JSON TEXT column.

```java
@MappedTypes(List.class)
public class JsonListTypeHandler extends BaseTypeHandler<List<String>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i,
                                    List<String> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to serialize list to JSON", e);
        }
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getString(columnName));
    }

    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getString(columnIndex));
    }

    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getString(columnIndex));
    }

    private List<String> parse(String json) throws SQLException {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to deserialize JSON to list", e);
        }
    }
}
```

Place in: `common/typehandler/JsonListTypeHandler.java`

---

## Related Skills

| Skill | When to load alongside this one |
|---|---|
| `web-infra` | Adding exception handling, new `ErrorCode`, interceptors, response helpers |
| `transaction-patterns` | Service has multiple DB writes that need to be atomic |
