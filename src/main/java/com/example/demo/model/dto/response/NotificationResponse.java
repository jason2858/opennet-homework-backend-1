package com.example.demo.model.dto.response;

import java.time.LocalDateTime;
import java.util.List;

public record NotificationResponse(
    Long id,
    String type,
    String recipient,
    String subject,
    String content,
    String status,
    String fromAddress,
    String replyTo,
    String senderId,
    List<String> cc,
    List<String> bcc,
    String contentType,
    List<String> attachmentUrls,
    int retryCount,
    String lastError,
    LocalDateTime scheduledAt,
    LocalDateTime sentAt,
    LocalDateTime deliveredAt,
    LocalDateTime readAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
