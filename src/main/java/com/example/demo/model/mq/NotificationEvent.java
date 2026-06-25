package com.example.demo.model.mq;

import java.time.LocalDateTime;

public record NotificationEvent(
    String action,
    Long id,
    String type,
    String recipient,
    String subject,
    String content,
    String fromAddress,
    String senderId,
    LocalDateTime occurredAt
) {}
