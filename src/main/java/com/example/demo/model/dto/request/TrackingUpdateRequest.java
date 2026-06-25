package com.example.demo.model.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TrackingUpdateRequest(
    @NotBlank(message = "event is required")
    @Pattern(regexp = "^(DELIVERED|READ)$", message = "event must be 'DELIVERED' or 'READ'")
    String event
) {}
