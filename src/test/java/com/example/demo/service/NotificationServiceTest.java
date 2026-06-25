package com.example.demo.service;

import com.example.demo.common.exception.NotificationNotFoundException;
import com.example.demo.model.dto.CreateResultDto;
import com.example.demo.model.dto.request.NotificationRequest;
import com.example.demo.model.dto.request.TrackingUpdateRequest;
import com.example.demo.model.dto.request.UpdateNotificationRequest;
import com.example.demo.model.dto.response.NotificationResponse;
import com.example.demo.model.entity.Notification;
import com.example.demo.service.core.NotificationCoreService;
import com.example.demo.common.converter.NotificationConverter;
import com.example.demo.util.NotificationRedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock private NotificationCoreService coreService;
    @Mock private NotificationRedisUtil cacheUtil;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private NotificationService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "defaultFromAddress", "no-reply@example.com");
    }

    private NotificationRequest emailRequest() {
        return new NotificationRequest("email", "user@test.com", "Hello", "Body", null, null, null);
    }

    private Notification pendingNotification(Long id) {
        Notification n = new Notification();
        n.setId(id);
        n.setType("email");
        n.setRecipient("user@test.com");
        n.setSubject("Hello");
        n.setContent("Body");
        n.setStatus("PENDING");
        n.setRetryCount(0);
        n.setFromAddress("no-reply@example.com");
        n.setContentType("text/plain");
        n.setCc(Collections.emptyList());
        n.setBcc(Collections.emptyList());
        n.setAttachments(Collections.emptyList());
        return n;
    }

    // --- create ---

    @Test
    void create_newNotification_returnsFalseReplayed() throws Exception {
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doNothing().when(coreService).insert(any());
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).cacheNotification(any());
        doNothing().when(cacheUtil).pushToRecent(any());

        CreateResultDto result = service.create(emailRequest(), null);

        assertThat(result.replayed()).isFalse();
        assertThat(result.response()).isNotNull();
        verify(coreService).insert(any());
    }

    @Test
    void create_withIdempotencyKey_storesKey() throws Exception {
        when(cacheUtil.getIdempotencyResult("idem-1")).thenReturn(Optional.empty());
        when(cacheUtil.tryClaimIdempotencyKey("idem-1")).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doNothing().when(coreService).insert(any());
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).cacheNotification(any());
        doNothing().when(cacheUtil).pushToRecent(any());
        doNothing().when(cacheUtil).resolveIdempotencyKey(anyString(), any());

        CreateResultDto result = service.create(emailRequest(), "idem-1");

        assertThat(result.replayed()).isFalse();
        verify(cacheUtil).resolveIdempotencyKey(eq("idem-1"), any());
    }

    @Test
    void create_withExistingIdempotencyKey_returnsReplayed() {
        Notification existing = pendingNotification(42L);
        existing.setStatus("SENT");
        NotificationResponse cachedResponse = NotificationConverter.toResponse(existing);

        when(cacheUtil.getIdempotencyResult("idem-1")).thenReturn(Optional.of(42L));
        when(cacheUtil.getCachedNotification(42L)).thenReturn(Optional.of(cachedResponse));

        CreateResultDto result = service.create(emailRequest(), "idem-1");

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().id()).isEqualTo(42L);
        verify(coreService, never()).insert(any());
    }

    @Test
    void create_idempotencyRaceResolvesAfterRetry() {
        Notification existing = pendingNotification(42L);
        existing.setStatus("SENT");
        NotificationResponse cachedResponse = NotificationConverter.toResponse(existing);

        when(cacheUtil.getIdempotencyResult("idem-race"))
            .thenReturn(Optional.empty())   // initial check — not resolved yet
            .thenReturn(Optional.of(42L));  // resolved during spin-wait
        when(cacheUtil.tryClaimIdempotencyKey("idem-race")).thenReturn(false);
        when(cacheUtil.getCachedNotification(42L)).thenReturn(Optional.of(cachedResponse));

        CreateResultDto result = service.create(emailRequest(), "idem-race");

        assertThat(result.replayed()).isTrue();
        assertThat(result.response().id()).isEqualTo(42L);
        verify(coreService, never()).insert(any());
    }

    @Test
    void create_scheduledNotification_skipsPublish() throws Exception {
        NotificationRequest scheduledReq = new NotificationRequest(
            "email", "user@test.com", "Hi", "Body",
            LocalDateTime.now().plusHours(1), null, null
        );
        doNothing().when(coreService).insert(any());

        CreateResultDto result = service.create(scheduledReq, null);

        assertThat(result.replayed()).isFalse();
        verify(rocketMQTemplate, never()).syncSend(anyString(), anyString());
    }

    // --- findById ---

    @Test
    void findById_cachedResult_returnsCached() {
        Notification n = pendingNotification(1L);
        NotificationResponse cached = NotificationConverter.toResponse(n);
        when(cacheUtil.getCachedNotification(1L)).thenReturn(Optional.of(cached));

        NotificationResponse result = service.findById(1L);

        assertThat(result.id()).isEqualTo(1L);
        verify(coreService, never()).findByIdOrThrow(any());
    }

    @Test
    void findById_notCached_acquiresLockAndQueriesDb() {
        Notification n = pendingNotification(1L);
        when(cacheUtil.getCachedNotification(1L)).thenReturn(Optional.empty());
        when(cacheUtil.isNullCached(1L)).thenReturn(false);
        when(cacheUtil.tryAcquireLock(1L)).thenReturn(true);
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);
        doNothing().when(cacheUtil).cacheNotification(any());

        NotificationResponse result = service.findById(1L);

        assertThat(result.id()).isEqualTo(1L);
        verify(coreService).findByIdOrThrow(1L);
        verify(cacheUtil).releaseLock(1L);
    }

    @Test
    void findById_lockContested_retriesAndFindsInCache() {
        Notification n = pendingNotification(1L);
        NotificationResponse response = NotificationConverter.toResponse(n);
        when(cacheUtil.getCachedNotification(1L))
            .thenReturn(Optional.empty())   // initial check
            .thenReturn(Optional.of(response)); // first retry
        when(cacheUtil.isNullCached(1L)).thenReturn(false);
        when(cacheUtil.tryAcquireLock(1L)).thenReturn(false);

        NotificationResponse result = service.findById(1L);

        assertThat(result.id()).isEqualTo(1L);
        verify(coreService, never()).findByIdOrThrow(any());
    }

    @Test
    void findById_notFound_acquiresLockCachesNullResult() {
        when(cacheUtil.getCachedNotification(99L)).thenReturn(Optional.empty());
        when(cacheUtil.isNullCached(99L)).thenReturn(false);
        when(cacheUtil.tryAcquireLock(99L)).thenReturn(true);
        when(coreService.findByIdOrThrow(99L)).thenThrow(new NotificationNotFoundException(99L));
        doNothing().when(cacheUtil).cacheNotFound(99L);

        assertThatThrownBy(() -> service.findById(99L))
            .isInstanceOf(NotificationNotFoundException.class);
        verify(cacheUtil).cacheNotFound(99L);
        verify(cacheUtil).releaseLock(99L);
    }

    @Test
    void findById_nullCached_throwsWithoutHittingDb() {
        when(cacheUtil.getCachedNotification(99L)).thenReturn(Optional.empty());
        when(cacheUtil.isNullCached(99L)).thenReturn(true);

        assertThatThrownBy(() -> service.findById(99L))
            .isInstanceOf(NotificationNotFoundException.class);
        verify(coreService, never()).findByIdOrThrow(any());
    }

    // --- getRecent ---

    @Test
    void getRecent_cachedList_returnsCached() {
        Notification n = pendingNotification(1L);
        List<NotificationResponse> cached = List.of(NotificationConverter.toResponse(n));
        when(cacheUtil.getRecent()).thenReturn(cached);

        List<NotificationResponse> result = service.getRecent();

        assertThat(result).hasSize(1);
        verify(coreService, never()).findRecent();
    }

    @Test
    void getRecent_emptyCache_queriesDb() {
        Notification n = pendingNotification(1L);
        when(cacheUtil.getRecent()).thenReturn(Collections.emptyList());
        when(coreService.findRecent()).thenReturn(List.of(n));
        doNothing().when(cacheUtil).rebuildRecent(any());

        List<NotificationResponse> result = service.getRecent();

        assertThat(result).hasSize(1);
        verify(coreService).findRecent();
    }

    // --- update ---

    @Test
    void update_validRequest_updatesAndEvicts() {
        Notification n = pendingNotification(1L);
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).evictNotification(1L);
        doNothing().when(cacheUtil).evictRecent();

        NotificationResponse result = service.update(1L, new UpdateNotificationRequest("New Subject", "New Content"));

        assertThat(result.subject()).isEqualTo("New Subject");
        assertThat(result.content()).isEqualTo("New Content");
    }

    // --- delete ---

    @Test
    void delete_existingNotification_setsDeletedAt() {
        Notification n = pendingNotification(1L);
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).evictNotification(1L);
        doNothing().when(cacheUtil).evictRecent();

        service.delete(1L);

        assertThat(n.getDeletedAt()).isNotNull();
        verify(coreService).update(n);
    }

    @Test
    void delete_notFound_throws() {
        when(coreService.findByIdOrThrow(99L)).thenThrow(new NotificationNotFoundException(99L));

        assertThatThrownBy(() -> service.delete(99L))
            .isInstanceOf(NotificationNotFoundException.class);
    }

    // --- retry ---

    @Test
    void retry_failedNotification_incrementsRetryCount() throws Exception {
        Notification n = pendingNotification(1L);
        n.setStatus("FAILED");
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).evictNotification(1L);
        doNothing().when(cacheUtil).evictRecent();

        NotificationResponse result = service.retry(1L);

        assertThat(result).isNotNull();
        assertThat(n.getRetryCount()).isEqualTo(1);
    }

    @Test
    void retry_nonFailedNotification_throwsApiException() {
        Notification n = pendingNotification(1L);
        n.setStatus("PENDING");
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);

        assertThatThrownBy(() -> service.retry(1L))
            .isInstanceOf(com.example.demo.common.exception.ApiException.class);
    }

    // --- updateTracking ---

    @Test
    void updateTracking_delivered_setsDeliveredAt() {
        Notification n = pendingNotification(1L);
        n.setStatus("SENT");
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).cacheNotification(any());

        NotificationResponse result = service.updateTracking(1L, new TrackingUpdateRequest("DELIVERED"));

        assertThat(n.getDeliveredAt()).isNotNull();
        assertThat(result).isNotNull();
    }

    @Test
    void updateTracking_read_setsReadAt() {
        Notification n = pendingNotification(1L);
        n.setStatus("SENT");
        when(coreService.findByIdOrThrow(1L)).thenReturn(n);
        doNothing().when(coreService).update(any());
        doNothing().when(cacheUtil).cacheNotification(any());

        service.updateTracking(1L, new TrackingUpdateRequest("READ"));

        assertThat(n.getReadAt()).isNotNull();
    }
}
