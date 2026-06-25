---
name: web-infra
description: Cross-cutting HTTP infrastructure patterns for Spring Boot — exception hierarchy, uniform error responses, response helpers, HandlerInterceptor vs Filter decision guide, and common interceptor patterns. Load when designing system-level HTTP concerns that span multiple features.
metadata:
  version: "1.0.0"
  domain: backend
  triggers: exception handling, interceptor, filter, error response, response handler, 錯誤處理, 攔截器
  role: reference
  scope: cross-cutting
---

# Web Infrastructure Patterns

Cross-cutting HTTP concerns that belong to the application skeleton, not individual features.
These patterns should be **designed once** (ideally in the first feature) and **reused** by all subsequent features.

---

## 1. Exception Hierarchy

### Design rule
Build a typed exception hierarchy backed by an `ErrorCode` enum. Every domain exception carries an HTTP status and a machine-readable code — controllers and services never set `HttpStatus` directly.

### Structure

```
ApiException (RuntimeException)          ← base, carries ErrorCode
├── NotFoundException                    ← 404 NOTIFICATION_NOT_FOUND
├── ConflictException                    ← 409 DUPLICATE_KEY
├── RateLimitExceededException           ← 429 RATE_LIMIT_EXCEEDED
└── ... (one class per domain error)
```

### ErrorCode enum

```java
@Getter
@RequiredArgsConstructor
public enum ErrorCode {
    // 4xx
    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR"),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND"),
    CONFLICT(HttpStatus.CONFLICT, "CONFLICT"),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED"),
    // 5xx
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR");

    private final HttpStatus status;
    private final String code;
}
```

**Rule:** Add a new entry for every distinct domain error. Use entity-scoped names (e.g., `NOTIFICATION_NOT_FOUND`) when the error is specific to one entity.

### ApiException base

```java
@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
```

### Domain exception (one per error case)

```java
public class NotificationNotFoundException extends ApiException {
    public NotificationNotFoundException(Long id) {
        super(ErrorCode.NOT_FOUND, ErrorMessage.NOTIFICATION_NOT_FOUND + id);
    }
}
```

### ErrorResponse record (uniform body)

```java
public record ErrorResponse(
    String errorCode,
    String message,
    Map<String, String> details,   // null for non-validation errors
    LocalDateTime timestamp
) {
    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.getCode(), message, null, LocalDateTime.now());
    }
    public static ErrorResponse ofValidation(Map<String, String> details) {
        return new ErrorResponse(ErrorCode.VALIDATION_ERROR.getCode(), "Validation failed", details, LocalDateTime.now());
    }
}
```

### GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Most specific first — Spring picks the closest match
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getErrorCode().getStatus())
            .body(ErrorResponse.of(ex.getErrorCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
            .forEach(e -> details.put(e.getField(), e.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ErrorResponse.ofValidation(details));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
            .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, ex.getMessage()));
    }

    // Catch-all — MUST be last
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        return ResponseEntity.internalServerError()
            .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Internal server error"));
    }
}
```

**Rules:**
- Handler order matters: most specific exception class first, `Exception.class` always last.
- Never expose internal details in production error messages — log them, return a safe message.
- Add `HttpRequestMethodNotSupportedException` handler explicitly, otherwise the catch-all returns 500.

---

## 2. Response Helpers

### When to use a static response helper class

Extract to a static helper when:
- The same response-building logic (status code + headers) is shared by multiple controllers, **or**
- The response carries meta-headers (e.g., `X-Idempotency-Replayed`) that controllers shouldn't need to know about.

Do NOT extract to a helper for simple `ResponseEntity.ok(body)` — that stays inline.

### Pattern

```java
public final class IdempotencyResponseHandler {

    public static final String REPLAYED_HEADER = "X-Idempotency-Replayed";

    public static <T> ResponseEntity<T> build(T body, boolean replayed) {
        if (replayed) {
            return ResponseEntity.ok()
                .header(REPLAYED_HEADER, "true")
                .body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private IdempotencyResponseHandler() {}   // utility class — no instantiation
}
```

**Usage in controller:**
```java
return IdempotencyResponseHandler.build(result.response(), result.replayed());
```

**Package:** `common/handler/`

---

## 3. HandlerInterceptor vs Filter — Decision Guide

### Quick decision table

| Need | Use |
|---|---|
| Block request before controller (auth check, rate limit) | `HandlerInterceptor.preHandle()` |
| Run logic after controller, **modify response headers** | `HandlerInterceptor.afterCompletion()` (headers only — body is already written) |
| Read or wrap the **response body** | `Filter` + `ContentCachingResponseWrapper` |
| Add a header to every response (e.g., correlation ID) | `Filter` |
| Timing / access log | `HandlerInterceptor.preHandle()` + `afterCompletion()` |
| JWT extraction / token parsing | `Filter` (runs before Spring Security, can set `SecurityContext`) |

### Critical limitation of `HandlerInterceptor.postHandle()`

`postHandle()` runs **after the controller returns but before the response body is written**. For `@RestController`, the `ModelAndView` parameter is always null and the response has NOT been committed yet — BUT Jackson serialization has already been triggered internally. **Do not use `postHandle()` to set headers for REST responses** — use `afterCompletion()` for that.

Rule: **`postHandle()` is only useful with `@Controller` + traditional view templates (Thymeleaf, JSP).**

### HandlerInterceptor skeleton

```java
@Component
public class RequestTimingInterceptor implements HandlerInterceptor {

    private static final String START_KEY = "requestStart";

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        req.setAttribute(START_KEY, System.currentTimeMillis());
        return true;   // return false to abort
    }

    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        long elapsed = System.currentTimeMillis() - (long) req.getAttribute(START_KEY);
        log.info("Request {} {} completed in {}ms", req.getMethod(), req.getRequestURI(), elapsed);
    }
}
```

Register with `WebMvcConfigurer`:
```java
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {
    private final RequestTimingInterceptor timingInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(timingInterceptor)
            .addPathPatterns("/api/**")
            .excludePathPatterns("/actuator/**");
    }
}
```

### Filter skeleton (for response body / headers on every request)

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)   // run before all other filters
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    private static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res,
                                    FilterChain chain) throws IOException, ServletException {
        String id = Optional.ofNullable(req.getHeader(HEADER))
            .filter(s -> !s.isBlank())   // reject blank header, generate fresh ID
            .orElse(UUID.randomUUID().toString());
        MDC.put(MDC_KEY, id);
        res.setHeader(HEADER, id);
        try {
            chain.doFilter(req, res);
        } finally {
            MDC.remove(MDC_KEY);   // always clean up MDC to avoid thread-pool leaks
        }
    }
}
```

---

## 4. In-Memory Rate Limiter Pattern

Use when you need IP-based (infrastructure) rate limiting without a Redis round-trip.

### Fixed-window implementation

```java
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    // Global rule applies across all clients combined
    private static final Rule GLOBAL_RULE = new Rule(200, 1_000);   // 200 req/s
    private static final Rule DEFAULT_RULE = new Rule(100, 60_000); // 100 req/min per IP
    // Path-specific overrides (exact match on URI)
    private static final Map<String, Rule> PATH_RULES = Map.of(
        "/api/notifications", new Rule(20, 60_000)
    );

    private final Window globalWindow = new Window();
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (!globalWindow.tryAcquire(GLOBAL_RULE.maxRequests(), GLOBAL_RULE.windowMs())) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Service rate limit exceeded");
        }
        String ip = resolveClientIp(req);
        Rule rule = PATH_RULES.getOrDefault(req.getRequestURI(), DEFAULT_RULE);
        Window window = windows.computeIfAbsent(ip + ":" + req.getRequestURI(), k -> new Window());
        if (!window.tryAcquire(rule.maxRequests(), rule.windowMs())) {
            throw new ApiException(ErrorCode.RATE_LIMIT_EXCEEDED, "Too many requests from " + ip);
        }
        return true;
    }

    // Evict idle windows to prevent unbounded map growth
    @Scheduled(fixedDelay = 120_000)
    public void evictExpiredWindows() {
        windows.entrySet().removeIf(e -> e.getValue().isExpired());
    }

    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        return (forwarded != null && !forwarded.isBlank())
            ? forwarded.split(",")[0].trim()
            : req.getRemoteAddr();
    }

    private record Rule(int maxRequests, long windowMs) {}

    private static class Window {
        private int count = 0;
        private long windowStart = System.currentTimeMillis();
        private long lastAccess = System.currentTimeMillis();

        synchronized boolean tryAcquire(int max, long windowMs) {
            long now = System.currentTimeMillis();
            lastAccess = now;
            if (now - windowStart >= windowMs) {
                count = 0;
                windowStart = now;
            }
            if (count >= max) return false;
            count++;
            return true;
        }

        synchronized boolean isExpired() {
            return System.currentTimeMillis() - lastAccess > 600_000; // 10 min idle
        }
    }
}
```

**Notes:**
- `synchronized` on `Window` methods is sufficient — each Window instance is only contended per IP+path, not globally.
- `@Scheduled` requires `@EnableScheduling` on a `@Configuration` class.
- `X-Forwarded-For` header can be spoofed; for production behind a trusted proxy, only read the first IP in the chain.

---

## 5. Design Checklist for New Features

When designing a new feature, answer these questions before writing code:

**Exception handling:**
- [ ] Does this feature introduce new error cases not covered by existing `ErrorCode` entries?
- [ ] Add new `ErrorCode` values and matching domain exception classes.

**Response patterns:**
- [ ] Does this endpoint return special response metadata (extra headers, conditional status codes)?
- [ ] If yes, extract to a static `*ResponseHandler` utility in `common/handler/`.

**Interceptors / Filters:**
- [ ] Does this feature need pre-request logic (auth, coarse rate-limiting)?
  → `HandlerInterceptor.preHandle()`
- [ ] Does this feature need post-response logging or cleanup?
  → `HandlerInterceptor.afterCompletion()`
- [ ] Does this feature require adding headers to every response or reading the response body?
  → `Filter`

**When NOT to add interceptors:**
- Do NOT add an interceptor for **business-level** rate limiting (e.g., per-recipient, per-account quota) — that belongs in the `Service` where business rules live.
- Do NOT add a filter for logic that only applies to one endpoint — handle it in the controller or service.

**Rate limiting: infrastructure vs business**
| Type | Where | Example |
|---|---|---|
| Infrastructure | `HandlerInterceptor` | 200 req/s global; 20 req/min per IP |
| Business | `Service` | 10 notifications/hour per recipient |

---

## 6. Related Skills

| Skill | When to load alongside this one |
|---|---|
| `coding-conventions` | Full project patterns (mapper, service, package structure, enums, constants) |
| `transaction-patterns` | When exception handling must consider rollback boundaries |
