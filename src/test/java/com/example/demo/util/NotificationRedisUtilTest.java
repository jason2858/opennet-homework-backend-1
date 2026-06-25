package com.example.demo.util;

import com.example.demo.model.dto.response.NotificationResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationRedisUtilTest {

    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private ListOperations<String, String> listOps;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private NotificationRedisUtil util;

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    private NotificationResponse sampleResponse(Long id) {
        return new NotificationResponse(
            id, "email", "user@test.com", "Hello", "Body",
            "SENT", "no-reply@example.com", null, null,
            Collections.emptyList(), Collections.emptyList(),
            "text/plain", Collections.emptyList(),
            0, null, null, LocalDateTime.now(),
            null, null, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // --- notification cache ---

    @Test
    void cacheNotification_storesWithJitteredTtl() throws Exception {
        NotificationResponse response = sampleResponse(1L);
        when(objectMapper.writeValueAsString(response)).thenReturn("{\"id\":1}");
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);

        util.cacheNotification(response);

        verify(valueOps).set(eq("notification:1"), eq("{\"id\":1}"), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertThat(ttlCaptor.getValue()).isBetween(50L, 70L);
    }

    @Test
    void getCachedNotification_hit_returnsResponse() throws Exception {
        NotificationResponse response = sampleResponse(1L);
        when(valueOps.get("notification:1")).thenReturn("{\"id\":1}");
        when(objectMapper.readValue("{\"id\":1}", NotificationResponse.class)).thenReturn(response);

        Optional<NotificationResponse> result = util.getCachedNotification(1L);

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(1L);
    }

    @Test
    void getCachedNotification_miss_returnsEmpty() {
        when(valueOps.get("notification:1")).thenReturn(null);

        assertThat(util.getCachedNotification(1L)).isEmpty();
    }

    @Test
    void getCachedNotification_nullSentinel_returnsEmpty() {
        when(valueOps.get("notification:1")).thenReturn("__NULL__");

        assertThat(util.getCachedNotification(1L)).isEmpty();
        verifyNoInteractions(objectMapper);
    }

    @Test
    void evictNotification_deletesKey() {
        util.evictNotification(1L);
        verify(redis).delete("notification:1");
    }

    // --- negative caching ---

    @Test
    void cacheNotFound_storesNullSentinel() {
        util.cacheNotFound(1L);
        verify(valueOps).set("notification:1", "__NULL__", 30L, TimeUnit.SECONDS);
    }

    @Test
    void isNullCached_sentinelPresent_returnsTrue() {
        when(valueOps.get("notification:1")).thenReturn("__NULL__");
        assertThat(util.isNullCached(1L)).isTrue();
    }

    @Test
    void isNullCached_absent_returnsFalse() {
        when(valueOps.get("notification:1")).thenReturn(null);
        assertThat(util.isNullCached(1L)).isFalse();
    }

    @Test
    void isNullCached_normalValue_returnsFalse() {
        when(valueOps.get("notification:1")).thenReturn("{\"id\":1}");
        assertThat(util.isNullCached(1L)).isFalse();
    }

    // --- cache breakdown mutex ---

    @Test
    void tryAcquireLock_success_returnsTrue() {
        when(valueOps.setIfAbsent(eq("lock:notification:1"), eq("1"), any(Duration.class)))
            .thenReturn(true);
        assertThat(util.tryAcquireLock(1L)).isTrue();
    }

    @Test
    void tryAcquireLock_alreadyLocked_returnsFalse() {
        when(valueOps.setIfAbsent(eq("lock:notification:1"), eq("1"), any(Duration.class)))
            .thenReturn(false);
        assertThat(util.tryAcquireLock(1L)).isFalse();
    }

    @Test
    void releaseLock_deletesLockKey() {
        util.releaseLock(1L);
        verify(redis).delete("lock:notification:1");
    }

    // --- idempotency ---

    @Test
    void tryClaimIdempotencyKey_notExists_returnsTrue() {
        when(valueOps.setIfAbsent(eq("idempotency:key-1"), eq("PENDING"), any(Duration.class)))
            .thenReturn(true);
        assertThat(util.tryClaimIdempotencyKey("key-1")).isTrue();
    }

    @Test
    void tryClaimIdempotencyKey_alreadyClaimed_returnsFalse() {
        when(valueOps.setIfAbsent(eq("idempotency:key-1"), eq("PENDING"), any(Duration.class)))
            .thenReturn(false);
        assertThat(util.tryClaimIdempotencyKey("key-1")).isFalse();
    }

    @Test
    void getIdempotencyResult_absent_returnsEmpty() {
        when(valueOps.get("idempotency:key-1")).thenReturn(null);
        assertThat(util.getIdempotencyResult("key-1")).isEmpty();
    }

    @Test
    void getIdempotencyResult_pending_returnsEmpty() {
        when(valueOps.get("idempotency:key-1")).thenReturn("PENDING");
        assertThat(util.getIdempotencyResult("key-1")).isEmpty();
    }

    @Test
    void getIdempotencyResult_resolved_returnsId() {
        when(valueOps.get("idempotency:key-1")).thenReturn("42");
        assertThat(util.getIdempotencyResult("key-1")).contains(42L);
    }

    @Test
    void resolveIdempotencyKey_setsIdWithTtl() {
        util.resolveIdempotencyKey("key-1", 42L);
        verify(valueOps).set("idempotency:key-1", "42", 86400L, TimeUnit.SECONDS);
    }

    @Test
    void getIdempotencyResult_malformedValue_returnsEmpty() {
        when(valueOps.get("idempotency:key-1")).thenReturn("not-a-number");
        assertThat(util.getIdempotencyResult("key-1")).isEmpty();
    }

    // --- scheduler lock ---

    @Test
    void tryAcquireSchedulerLock_success_returnsTrue() {
        when(valueOps.setIfAbsent(eq("scheduler:lock:notification:1"), eq("1"), any(Duration.class)))
            .thenReturn(true);
        assertThat(util.tryAcquireSchedulerLock(1L)).isTrue();
    }

    @Test
    void tryAcquireSchedulerLock_alreadyLocked_returnsFalse() {
        when(valueOps.setIfAbsent(eq("scheduler:lock:notification:1"), eq("1"), any(Duration.class)))
            .thenReturn(false);
        assertThat(util.tryAcquireSchedulerLock(1L)).isFalse();
    }

    // --- recent list ---

    @Test
    void getRecent_populated_returnsDeserializedList() throws Exception {
        NotificationResponse response = sampleResponse(1L);
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range("notifications:recent", 0, -1)).thenReturn(List.of("{\"id\":1}"));
        when(objectMapper.readValue("{\"id\":1}", NotificationResponse.class)).thenReturn(response);

        List<NotificationResponse> result = util.getRecent();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).id()).isEqualTo(1L);
    }

    @Test
    void getRecent_empty_returnsEmptyList() {
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range("notifications:recent", 0, -1)).thenReturn(Collections.emptyList());

        assertThat(util.getRecent()).isEmpty();
    }

    @Test
    void evictRecent_deletesKey() {
        util.evictRecent();
        verify(redis).delete("notifications:recent");
    }
}
