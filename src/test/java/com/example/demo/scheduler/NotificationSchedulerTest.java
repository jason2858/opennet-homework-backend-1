package com.example.demo.scheduler;

import com.example.demo.model.entity.Notification;
import com.example.demo.service.NotificationService;
import com.example.demo.service.core.NotificationCoreService;
import com.example.demo.util.NotificationRedisUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NotificationSchedulerTest {

    @Mock private NotificationCoreService coreService;
    @Mock private NotificationService service;
    @Mock private NotificationRedisUtil cacheUtil;

    @InjectMocks
    private NotificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "maxRetries", 3);
        ReflectionTestUtils.setField(scheduler, "stuckPendingThresholdMinutes", 5);
    }

    private Notification pendingNotification(Long id) {
        Notification n = new Notification();
        n.setId(id);
        n.setStatus("PENDING");
        return n;
    }

    @Test
    void recoverStuckPending_withStuckNotifications_marksAsFailed() {
        Notification n = pendingNotification(1L);
        when(coreService.findStuckPending(any())).thenReturn(List.of(n));

        scheduler.recoverStuckPending();

        assertThat(n.getStatus()).isEqualTo("FAILED");
        assertThat(n.getLastError()).contains("sweeper");
        verify(coreService).update(n);
        verify(cacheUtil).evictNotification(1L);
        verify(cacheUtil).evictRecent();
    }

    @Test
    void recoverStuckPending_noStuckNotifications_doesNothing() {
        when(coreService.findStuckPending(any())).thenReturn(Collections.emptyList());

        scheduler.recoverStuckPending();

        verify(coreService, never()).update(any());
        verify(cacheUtil, never()).evictRecent();
    }
}
