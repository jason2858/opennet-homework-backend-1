package com.example.demo.model.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateNotificationRequest(
    String subject,
    @NotBlank(message = "content is required") String content
) {}
