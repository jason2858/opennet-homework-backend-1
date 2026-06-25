package com.example.demo.model.entity;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    private Long id;
    private String type;
    private String recipient;
    private String subject;
    private String content;
    private String status;
    private String fromAddress;
    private String replyTo;
    private String senderId;
    private List<String> cc;
    private List<String> bcc;
    private String contentType;
    private List<String> attachments;
    private int retryCount;
    private String lastError;
    private LocalDateTime scheduledAt;
    private LocalDateTime sentAt;
    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    private String idempotencyKey;
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
