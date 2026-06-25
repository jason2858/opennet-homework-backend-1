package com.example.demo.util;

import com.example.demo.model.dto.response.NotificationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class NotificationRedisUtil {

    private static final Logger log = LoggerFactory.getLogger(NotificationRedisUtil.class);

    private static final String NOTIFICATION_PREFIX = "notification:";
    private static final String RECENT_KEY = "notifications:recent";
    private static final String IDEMPOTENCY_PREFIX = "idempotency:";
    private static final String IDEMPOTENCY_PENDING = "PENDING";
    private static final long NOTIFICATION_TTL_BASE = 60;
    private static final long NOTIFICATION_TTL_JITTER = 10;
    private static final long NULL_TTL_SECONDS = 30;
    private static final String NULL_SENTINEL = "__NULL__";
    private static final String LOCK_PREFIX = "lock:notification:";
    private static final long LOCK_TTL_SECONDS = 5;
    private static final String SCHEDULER_LOCK_PREFIX = "scheduler:lock:notification:";
    private static final long SCHEDULER_LOCK_TTL_SECONDS = 120;
    private static final long IDEMPOTENCY_TTL_SECONDS = 86400;
    private static final long IDEMPOTENCY_CLAIM_TTL_SECONDS = 60;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    // --- notification cache ---

    public void cacheNotification(NotificationResponse response) {
        try {
            long ttl = NOTIFICATION_TTL_BASE
                + ThreadLocalRandom.current().nextLong(-NOTIFICATION_TTL_JITTER, NOTIFICATION_TTL_JITTER + 1);
            redis.opsForValue().set(
                NOTIFICATION_PREFIX + response.id(),
                objectMapper.writeValueAsString(response),
                ttl, TimeUnit.SECONDS);
        } catch (JsonProcessingException e) {
            log.warn("Failed to cache notification {}", response.id(), e);
        }
    }

    public Optional<NotificationResponse> getCachedNotification(Long id) {
        try {
            String json = redis.opsForValue().get(NOTIFICATION_PREFIX + id);
            if (json == null || NULL_SENTINEL.equals(json)) return Optional.empty();
            return Optional.of(objectMapper.readValue(json, NotificationResponse.class));
        } catch (JsonProcessingException e) {
            log.warn("Failed to deserialize cached notification {}", id, e);
            return Optional.empty();
        }
    }

    public void cacheNotFound(Long id) {
        redis.opsForValue().set(NOTIFICATION_PREFIX + id, NULL_SENTINEL, NULL_TTL_SECONDS, TimeUnit.SECONDS);
    }

    public boolean isNullCached(Long id) {
        return NULL_SENTINEL.equals(redis.opsForValue().get(NOTIFICATION_PREFIX + id));
    }

    public void evictNotification(Long id) {
        redis.delete(NOTIFICATION_PREFIX + id);
    }

    // --- recent list ---

    public void pushToRecent(NotificationResponse response) {
        try {
            redis.execute(NotificationRedisScripts.PUSH_RECENT,
                List.of(RECENT_KEY), objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.warn("Failed to push notification {} to recent list", response.id(), e);
        }
    }

    public List<NotificationResponse> getRecent() {
        try {
            List<String> jsons = redis.opsForList().range(RECENT_KEY, 0, -1);
            if (jsons == null || jsons.isEmpty()) return Collections.emptyList();
            List<NotificationResponse> result = new ArrayList<>();
            for (String json : jsons) {
                result.add(objectMapper.readValue(json, NotificationResponse.class));
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to get recent notifications from Redis", e);
            return Collections.emptyList();
        }
    }

    public void evictRecent() {
        redis.delete(RECENT_KEY);
    }

    public void rebuildRecent(List<NotificationResponse> notifications) {
        try {
            evictRecent();
            // LPUSH oldest-first so newest ends up at list head
            for (int i = notifications.size() - 1; i >= 0; i--) {
                redis.opsForList().leftPush(RECENT_KEY, objectMapper.writeValueAsString(notifications.get(i)));
            }
        } catch (Exception e) {
            log.warn("Failed to rebuild recent notifications in Redis", e);
        }
    }

    // --- cache breakdown mutex ---

    public boolean tryAcquireLock(Long id) {
        return Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(LOCK_PREFIX + id, "1", Duration.ofSeconds(LOCK_TTL_SECONDS))
        );
    }

    public void releaseLock(Long id) {
        redis.delete(LOCK_PREFIX + id);
    }

    public boolean tryAcquireSchedulerLock(Long id) {
        return Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(SCHEDULER_LOCK_PREFIX + id, "1", Duration.ofSeconds(SCHEDULER_LOCK_TTL_SECONDS))
        );
    }

    // --- idempotency ---

    /**
     * Atomically claims the idempotency slot using SET NX.
     * Returns true if this request successfully claimed the slot (proceed with creation).
     * Returns false if another request already holds or held this key.
     */
    public boolean tryClaimIdempotencyKey(String key) {
        return Boolean.TRUE.equals(
            redis.opsForValue().setIfAbsent(
                IDEMPOTENCY_PREFIX + key,
                IDEMPOTENCY_PENDING,
                Duration.ofSeconds(IDEMPOTENCY_CLAIM_TTL_SECONDS)
            )
        );
    }

    /**
     * Returns the resolved notification ID for a key, or empty if not yet resolved
     * (key absent or still PENDING from an in-flight request).
     */
    public Optional<Long> getIdempotencyResult(String key) {
        String value = redis.opsForValue().get(IDEMPOTENCY_PREFIX + key);
        if (value == null || IDEMPOTENCY_PENDING.equals(value)) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            log.warn("Malformed idempotency value for key {}: {}", key, value);
            return Optional.empty();
        }
    }

    /**
     * Replaces the PENDING placeholder with the real notification ID after creation succeeds.
     */
    public void resolveIdempotencyKey(String key, Long notificationId) {
        redis.opsForValue().set(
            IDEMPOTENCY_PREFIX + key,
            String.valueOf(notificationId),
            IDEMPOTENCY_TTL_SECONDS, TimeUnit.SECONDS
        );
    }
}
