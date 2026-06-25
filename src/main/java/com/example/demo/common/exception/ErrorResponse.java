package com.example.demo.common.exception;

import java.time.LocalDateTime;
import java.util.Map;

public record ErrorResponse(
    String errorCode,
    String message,
    Map<String, String> details,
    LocalDateTime timestamp
) {
    public static ErrorResponse of(ErrorCode code, String message) {
        return new ErrorResponse(code.getCode(), message, null, LocalDateTime.now());
    }

    public static ErrorResponse ofValidation(Map<String, String> details) {
        return new ErrorResponse(ErrorCode.VALIDATION_ERROR.getCode(), "Validation failed", details, LocalDateTime.now());
    }
}
