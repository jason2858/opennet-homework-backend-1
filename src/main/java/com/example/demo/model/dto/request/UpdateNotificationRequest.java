package com.example.demo.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateNotificationRequest(
    @Size(max = 255) String subject,
    @NotBlank(message = "content is required") @Size(max = 50000) String content
) {}
