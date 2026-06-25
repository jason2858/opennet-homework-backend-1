package com.example.demo.model.dto;

import com.example.demo.model.dto.response.NotificationResponse;

public record CreateResultDto(
        NotificationResponse response,
        boolean replayed
) {}
