package com.example.demo.scheduler;

import com.example.demo.model.entity.Notification;
import com.example.demo.model.enums.NotificationStatus;
import com.example.demo.service.core.NotificationCoreService;
import com.example.demo.service.NotificationService;
import com.example.demo.util.NotificationRedisUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class NotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(NotificationScheduler.class);

    private final NotificationCoreService coreService;
    private final NotificationService service;
    private final NotificationRedisUtil cacheUtil;

    @Value("${notification.scheduler.max-retries:3}")
    private int maxRetries;

    @Value("${notification.scheduler.stuck-pending-threshold-minutes:5}")
    private int stuckPendingThresholdMinutes;

    @Scheduled(fixedDelay = 120_000)
    public void recoverStuckPending() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(stuckPendingThresholdMinutes);
        List<Notification> stuck = coreService.findStuckPending(threshold);
        if (stuck.isEmpty()) return;

        log.warn("Recovering {} stuck PENDING notification(s) older than {} minutes",
            stuck.size(), stuckPendingThresholdMinutes);
        for (Notification notification : stuck) {
            notification.setStatus(NotificationStatus.FAILED.name());
            notification.setLastError("Recovered by sweeper: PENDING exceeded threshold");
            coreService.update(notification);
            cacheUtil.evictNotification(notification.getId());
        }
        cacheUtil.evictRecent();
    }

    @Scheduled(fixedDelay = 60000)
    public void processScheduledNotifications() {
        List<Notification> pending = coreService.findPendingScheduled(LocalDateTime.now(), maxRetries);
        if (pending.isEmpty()) return;

        log.info("Processing {} scheduled notification(s)", pending.size());
        for (Notification notification : pending) {
            if (!cacheUtil.tryAcquireSchedulerLock(notification.getId())) {
                log.debug("Skipping notification {} — already claimed by another instance", notification.getId());
                continue;
            }
            try {
                service.processScheduled(notification);
            } catch (Exception e) {
                log.error("Failed to process scheduled notification {}", notification.getId(), e);
            }
        }
    }
}
