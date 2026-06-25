package com.example.demo.common.converter;

import com.example.demo.model.dto.response.NotificationResponse;
import com.example.demo.model.entity.Notification;

import java.util.Collections;

public final class NotificationConverter {

    public static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(
            n.getId(), n.getType(), n.getRecipient(), n.getSubject(), n.getContent(),
            n.getStatus(), n.getFromAddress(), n.getReplyTo(), n.getSenderId(),
            n.getCc() != null ? n.getCc() : Collections.emptyList(),
            n.getBcc() != null ? n.getBcc() : Collections.emptyList(),
            n.getContentType(),
            n.getAttachments() != null ? n.getAttachments() : Collections.emptyList(),
            n.getRetryCount(), n.getLastError(), n.getScheduledAt(), n.getSentAt(),
            n.getDeliveredAt(), n.getReadAt(), n.getCreatedAt(), n.getUpdatedAt()
        );
    }

    private NotificationConverter() {}
}
