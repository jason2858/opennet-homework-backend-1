package com.example.demo.model.dto.request;

import com.example.demo.model.dto.EmailOptionsDto;
import com.example.demo.model.dto.SmsOptionsDto;
import com.example.demo.validator.ValidNotificationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@ValidNotificationRequest
public record NotificationRequest(
    @NotBlank(message = "type is required")
    @Pattern(regexp = "^(email|sms)$", message = "type must be 'email' or 'sms'")
    String type,

    @NotBlank(message = "recipient is required")
    @Size(max = 255, message = "recipient must not exceed 255 characters")
    String recipient,

    @Size(max = 255, message = "subject must not exceed 255 characters")
    String subject,

    @NotBlank(message = "content is required")
    @Size(max = 50000, message = "content must not exceed 50000 characters")
    String content,

    LocalDateTime scheduledAt,

    @Valid EmailOptionsDto emailOptions,
    @Valid SmsOptionsDto smsOptions
) {}
