package com.example.demo.model.dto;

import jakarta.validation.constraints.Size;

public record SmsOptionsDto(
    @Size(max = 11, message = "senderId must not exceed 11 characters")
    String senderId
) {}
