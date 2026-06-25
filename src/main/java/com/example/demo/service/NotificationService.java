package com.example.demo.service;

import com.example.demo.common.converter.NotificationConverter;
import com.example.demo.common.exception.ApiException;
import com.example.demo.common.exception.ErrorCode;
import com.example.demo.common.exception.NotificationNotFoundException;
import com.example.demo.constants.ErrorMessage;
import com.example.demo.constants.MqConstants;
import com.example.demo.model.dto.CreateResultDto;
import com.example.demo.model.dto.EmailOptionsDto;
import com.example.demo.model.dto.request.NotificationRequest;
import com.example.demo.model.dto.request.TrackingUpdateRequest;
import com.example.demo.model.dto.request.UpdateNotificationRequest;
import com.example.demo.model.dto.response.NotificationResponse;
import com.example.demo.model.entity.Notification;
import com.example.demo.model.enums.NotificationAction;
import com.example.demo.model.enums.NotificationStatus;
import com.example.demo.model.enums.NotificationType;
import com.example.demo.model.enums.TrackingEvent;
import com.example.demo.model.mq.NotificationEvent;
import com.example.demo.service.core.NotificationCoreService;
import com.example.demo.util.NotificationRedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String DEFAULT_CONTENT_TYPE = "text/plain";
    private static final String DEFAULT_SMS_SUBJECT = "無主旨";

    private final NotificationCoreService coreService;
    private final NotificationRedisUtil cacheUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    @Value("${notification.default-from-address:no-reply@example.com}")
    private String defaultFromAddress;

    public CreateResultDto create(NotificationRequest request, String idempotencyKey) {
        if (idempotencyKey != null) {
            Optional<Long> existing = cacheUtil.getIdempotencyResult(idempotencyKey);
            if (existing.isPresent()) {
                return new CreateResultDto(findById(existing.get()), true);
            }
            // Atomically claim the slot; if we lose the race, check once more for a resolved result
            if (!cacheUtil.tryClaimIdempotencyKey(idempotencyKey)) {
                for (int i = 0; i < 3; i++) {
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    Optional<Long> raced = cacheUtil.getIdempotencyResult(idempotencyKey);
                    if (raced.isPresent()) return new CreateResultDto(findById(raced.get()), true);
                }
                // Lock holder likely crashed — proceed as independent request
            }
        }

        Notification notification = buildNotification(request);
        if (idempotencyKey != null) notification.setIdempotencyKey(idempotencyKey);
        coreService.insert(notification);

        if (request.scheduledAt() != null) {
            if (idempotencyKey != null) cacheUtil.resolveIdempotencyKey(idempotencyKey, notification.getId());
            return new CreateResultDto(NotificationConverter.toResponse(notification), false);
        }

        publishAndUpdateStatus(notification, NotificationAction.CREATE);
        coreService.update(notification);

        NotificationResponse response = NotificationConverter.toResponse(notification);
        cacheUtil.cacheNotification(response);
        cacheUtil.pushToRecent(response);

        if (idempotencyKey != null) cacheUtil.resolveIdempotencyKey(idempotencyKey, notification.getId());
        return new CreateResultDto(response, false);
    }

    public NotificationResponse findById(Long id) {
        Optional<NotificationResponse> cached = cacheUtil.getCachedNotification(id);
        if (cached.isPresent()) return cached.get();
        if (cacheUtil.isNullCached(id)) throw new NotificationNotFoundException(id);

        if (!cacheUtil.tryAcquireLock(id)) {
            return findByIdWithRetry(id);
        }
        try {
            // Double-check after acquiring lock — another thread may have just populated cache
            Optional<NotificationResponse> recheck = cacheUtil.getCachedNotification(id);
            if (recheck.isPresent()) return recheck.get();
            return loadFromDb(id);
        } finally {
            cacheUtil.releaseLock(id);
        }
    }

    private NotificationResponse findByIdWithRetry(Long id) {
        for (int i = 0; i < 3; i++) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Optional<NotificationResponse> retry = cacheUtil.getCachedNotification(id);
            if (retry.isPresent()) return retry.get();
            if (cacheUtil.isNullCached(id)) throw new NotificationNotFoundException(id);
        }
        return loadFromDb(id); // safety fallback: lock holder may have crashed
    }

    private NotificationResponse loadFromDb(Long id) {
        try {
            NotificationResponse response = NotificationConverter.toResponse(coreService.findByIdOrThrow(id));
            cacheUtil.cacheNotification(response);
            return response;
        } catch (NotificationNotFoundException e) {
            cacheUtil.cacheNotFound(id);
            throw e;
        }
    }

    public List<NotificationResponse> getRecent() {
        List<NotificationResponse> cached = cacheUtil.getRecent();
        if (!cached.isEmpty()) return cached;

        List<NotificationResponse> responses = coreService.findRecent().stream()
            .map(NotificationConverter::toResponse)
            .toList();

        cacheUtil.rebuildRecent(responses);
        return responses;
    }

    public NotificationResponse update(Long id, UpdateNotificationRequest request) {
        Notification notification = coreService.findByIdOrThrow(id);
        notification.setSubject(request.subject());
        notification.setContent(request.content());
        publishAndUpdateStatus(notification, NotificationAction.UPDATE);
        coreService.update(notification);

        cacheUtil.evictNotification(id);
        cacheUtil.evictRecent();
        return NotificationConverter.toResponse(notification);
    }

    public void delete(Long id) {
        Notification notification = coreService.findByIdOrThrow(id);
        notification.setDeletedAt(LocalDateTime.now());
        coreService.update(notification);

        cacheUtil.evictNotification(id);
        cacheUtil.evictRecent();
    }

    public NotificationResponse retry(Long id) {
        Notification notification = coreService.findByIdOrThrow(id);
        if (!NotificationStatus.FAILED.name().equals(notification.getStatus())) {
            throw new ApiException(ErrorCode.INVALID_STATUS_TRANSITION, ErrorMessage.NOT_IN_FAILED_STATUS);
        }

        notification.setRetryCount(notification.getRetryCount() + 1);
        publishAndUpdateStatus(notification, NotificationAction.RETRY);
        coreService.update(notification);

        cacheUtil.evictNotification(id);
        cacheUtil.evictRecent();
        return NotificationConverter.toResponse(notification);
    }

    public NotificationResponse updateTracking(Long id, TrackingUpdateRequest request) {
        Notification notification = coreService.findByIdOrThrow(id);
        LocalDateTime now = LocalDateTime.now();
        switch (TrackingEvent.valueOf(request.event())) {
            case DELIVERED -> notification.setDeliveredAt(now);
            case READ -> notification.setReadAt(now);
            default -> throw new IllegalArgumentException("Unhandled tracking event: " + request.event());
        }
        coreService.update(notification);

        NotificationResponse response = NotificationConverter.toResponse(notification);
        cacheUtil.cacheNotification(response);
        return response;
    }

    public void processScheduled(Notification notification) {
        publishAndUpdateStatus(notification, NotificationAction.CREATE);
        coreService.update(notification);
        if (NotificationStatus.SENT.name().equals(notification.getStatus())) {
            NotificationResponse response = NotificationConverter.toResponse(notification);
            cacheUtil.cacheNotification(response);
            cacheUtil.evictRecent();
        }
    }

    private void publishAndUpdateStatus(Notification notification, NotificationAction action) {
        try {
            LocalDateTime now = LocalDateTime.now();
            NotificationEvent event = new NotificationEvent(
                action.name(),
                notification.getId(),
                notification.getType(),
                notification.getRecipient(),
                notification.getSubject(),
                notification.getContent(),
                notification.getFromAddress(),
                notification.getSenderId(),
                now
            );
            rocketMQTemplate.syncSend(MqConstants.NOTIFICATION_TOPIC, objectMapper.writeValueAsString(event));
            notification.setStatus(NotificationStatus.SENT.name());
            notification.setSentAt(now);
        } catch (Exception e) {
            log.warn("RocketMQ publish failed for notification {}: {}", notification.getId(), e.getMessage());
            notification.setStatus(NotificationStatus.FAILED.name());
            notification.setLastError(truncate(e.getMessage(), 500));
        }
    }

    private Notification buildNotification(NotificationRequest req) {
        Notification n = new Notification();
        n.setType(req.type());
        n.setRecipient(req.recipient());

        String subject = req.subject();
        if ((subject == null || subject.isBlank()) && NotificationType.SMS.name().equalsIgnoreCase(req.type())) {
            subject = DEFAULT_SMS_SUBJECT;
        }
        n.setSubject(subject);

        n.setContent(req.content());
        n.setStatus(req.scheduledAt() != null
            ? NotificationStatus.SCHEDULED.name()
            : NotificationStatus.PENDING.name());
        n.setRetryCount(0);
        n.setScheduledAt(req.scheduledAt());

        if (req.emailOptions() != null) {
            EmailOptionsDto o = req.emailOptions();
            n.setFromAddress(o.fromAddress() != null ? o.fromAddress() : defaultFromAddress);
            n.setReplyTo(o.replyTo());
            n.setCc(o.cc() != null ? o.cc() : Collections.emptyList());
            n.setBcc(o.bcc() != null ? o.bcc() : Collections.emptyList());
            n.setContentType(o.contentType() != null ? o.contentType() : DEFAULT_CONTENT_TYPE);
            n.setAttachments(o.attachmentUrls() != null ? o.attachmentUrls() : Collections.emptyList());
        } else if (NotificationType.EMAIL.name().equalsIgnoreCase(req.type())) {
            n.setFromAddress(defaultFromAddress);
            n.setContentType(DEFAULT_CONTENT_TYPE);
            n.setCc(Collections.emptyList());
            n.setBcc(Collections.emptyList());
            n.setAttachments(Collections.emptyList());
        } else {
            n.setContentType(DEFAULT_CONTENT_TYPE);
            n.setCc(Collections.emptyList());
            n.setBcc(Collections.emptyList());
            n.setAttachments(Collections.emptyList());
        }

        if (req.smsOptions() != null) n.setSenderId(req.smsOptions().senderId());
        return n;
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
