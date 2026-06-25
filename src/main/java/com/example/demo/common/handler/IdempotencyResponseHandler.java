package com.example.demo.common.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

public final class IdempotencyResponseHandler {

    public static final String REPLAYED_HEADER = "X-Idempotency-Replayed";

    public static <T> ResponseEntity<T> build(T body, boolean replayed) {
        if (replayed) {
            return ResponseEntity.ok()
                .header(REPLAYED_HEADER, "true")
                .body(body);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    private IdempotencyResponseHandler() {}
}
