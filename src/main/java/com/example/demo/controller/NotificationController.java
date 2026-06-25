package com.example.demo.controller;

import com.example.demo.model.dto.CreateResultDto;
import com.example.demo.model.dto.request.NotificationRequest;
import com.example.demo.model.dto.request.TrackingUpdateRequest;
import com.example.demo.model.dto.request.UpdateNotificationRequest;
import com.example.demo.model.dto.response.NotificationResponse;
import com.example.demo.common.handler.IdempotencyResponseHandler;
import com.example.demo.service.NotificationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @PostMapping
    public ResponseEntity<NotificationResponse> create(
        @Valid @RequestBody NotificationRequest request,
        @RequestHeader(value = "X-Idempotency-Key", required = false)
        @Size(max = 64, message = "X-Idempotency-Key must not exceed 64 characters") String idempotencyKey
    ) {
        CreateResultDto result = service.create(request, idempotencyKey);
        return IdempotencyResponseHandler.build(result.response(), result.replayed());
    }

    @GetMapping("/{id}")
    public NotificationResponse getById(@PathVariable Long id) {
        return service.findById(id);
    }

    @GetMapping("/recent")
    public List<NotificationResponse> getRecent() {
        return service.getRecent();
    }

    @PutMapping("/{id}")
    public NotificationResponse update(
        @PathVariable Long id,
        @Valid @RequestBody UpdateNotificationRequest request
    ) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        service.delete(id);
    }

    /**
     * 重試失敗的通知。
     * 僅允許 status = FAILED 的通知呼叫，每次重試會將 retryCount + 1 並重新發送 MQ 事件。
     * 若通知不處於 FAILED 狀態，回傳 409 CONFLICT。
     */
    @PostMapping("/{id}/retry")
    public NotificationResponse retry(@PathVariable Long id) {
        return service.retry(id);
    }

    /**
     * 更新通知的送達追蹤狀態。
     * 設計供外部系統（Email/SMS provider 的 webhook callback）呼叫，用來記錄送達或已讀時間。
     * 支援兩種事件：DELIVERED（訊息到達收件人裝置）、READ（收件人開啟訊息）。
     */
    @PatchMapping("/{id}/tracking")
    public NotificationResponse updateTracking(
        @PathVariable Long id,
        @Valid @RequestBody TrackingUpdateRequest request
    ) {
        return service.updateTracking(id, request);
    }
}
