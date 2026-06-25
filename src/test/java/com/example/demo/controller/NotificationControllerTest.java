package com.example.demo.controller;

import com.example.demo.common.exception.GlobalExceptionHandler;
import com.example.demo.common.exception.NotificationNotFoundException;
import com.example.demo.model.dto.CreateResultDto;
import com.example.demo.model.dto.response.NotificationResponse;
import com.example.demo.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@Import(GlobalExceptionHandler.class)
class NotificationControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private NotificationService service;

    private NotificationResponse sampleResponse(Long id) {
        return new NotificationResponse(
            id, "email", "user@test.com", "Hello", "Body",
            "SENT", "no-reply@example.com", null, null,
            Collections.emptyList(), Collections.emptyList(),
            "text/plain", Collections.emptyList(),
            0, null, null, LocalDateTime.now(),
            null, null, LocalDateTime.now(), LocalDateTime.now()
        );
    }

    // --- POST /notifications ---

    @Test
    void create_validRequest_returns201() throws Exception {
        NotificationResponse resp = sampleResponse(1L);
        when(service.create(any(), any())).thenReturn(new CreateResultDto(resp, false));

        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"email","recipient":"user@test.com","content":"Body"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void create_replayedIdempotency_returns200WithHeader() throws Exception {
        NotificationResponse resp = sampleResponse(1L);
        when(service.create(any(), eq("idem-1"))).thenReturn(new CreateResultDto(resp, true));

        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", "idem-1")
                .content("""
                    {"type":"email","recipient":"user@test.com","content":"Body"}
                    """))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Idempotency-Replayed", "true"));
    }

    @Test
    void create_missingRequiredField_returns400() throws Exception {
        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"email","recipient":"user@test.com"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void create_invalidType_returns400() throws Exception {
        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"type":"fax","recipient":"user@test.com","content":"Body"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    // --- GET /notifications/{id} ---

    @Test
    void getById_existingId_returns200() throws Exception {
        when(service.findById(1L)).thenReturn(sampleResponse(1L));

        mockMvc.perform(get("/api/notifications/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(service.findById(99L)).thenThrow(new NotificationNotFoundException(99L));

        mockMvc.perform(get("/api/notifications/99"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.errorCode").value("NOTIFICATION_NOT_FOUND"));
    }

    // --- GET /notifications/recent ---

    @Test
    void getRecent_returns200WithList() throws Exception {
        when(service.getRecent()).thenReturn(List.of(sampleResponse(1L)));

        mockMvc.perform(get("/api/notifications/recent"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value(1));
    }

    // --- PUT /notifications/{id} ---

    @Test
    void update_validRequest_returns200() throws Exception {
        when(service.update(eq(1L), any())).thenReturn(sampleResponse(1L));

        mockMvc.perform(put("/api/notifications/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"Updated Body"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void update_missingContent_returns400() throws Exception {
        mockMvc.perform(put("/api/notifications/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"subject":"New Subject"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void update_notFound_returns404() throws Exception {
        when(service.update(eq(99L), any())).thenThrow(new NotificationNotFoundException(99L));

        mockMvc.perform(put("/api/notifications/99")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"content":"Body"}
                    """))
            .andExpect(status().isNotFound());
    }

    // --- DELETE /notifications/{id} ---

    @Test
    void delete_existingId_returns204() throws Exception {
        doNothing().when(service).delete(1L);

        mockMvc.perform(delete("/api/notifications/1"))
            .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        doThrow(new NotificationNotFoundException(99L)).when(service).delete(99L);

        mockMvc.perform(delete("/api/notifications/99"))
            .andExpect(status().isNotFound());
    }

    // --- POST /notifications/{id}/retry ---

    @Test
    void retry_failedNotification_returns200() throws Exception {
        when(service.retry(1L)).thenReturn(sampleResponse(1L));

        mockMvc.perform(post("/api/notifications/1/retry"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void retry_nonFailedNotification_returns409() throws Exception {
        when(service.retry(1L)).thenThrow(
            new com.example.demo.common.exception.ApiException(
                com.example.demo.common.exception.ErrorCode.INVALID_STATUS_TRANSITION,
                "Notification is not in FAILED status"));

        mockMvc.perform(post("/api/notifications/1/retry"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.errorCode").value("INVALID_STATUS_TRANSITION"));
    }

    // --- PATCH /notifications/{id}/tracking ---

    @Test
    void updateTracking_delivered_returns200() throws Exception {
        when(service.updateTracking(eq(1L), any())).thenReturn(sampleResponse(1L));

        mockMvc.perform(patch("/api/notifications/1/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"event":"DELIVERED"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1));
    }

    @Test
    void updateTracking_invalidEvent_returns400() throws Exception {
        mockMvc.perform(patch("/api/notifications/1/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"event":"OPENED"}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void updateTracking_missingEvent_returns400() throws Exception {
        mockMvc.perform(patch("/api/notifications/1/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    @Test
    void create_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{ invalid json }"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.errorCode").value("INVALID_ARGUMENT"));
    }

    @Test
    void create_idempotencyKeyTooLong_returns400() throws Exception {
        mockMvc.perform(post("/api/notifications")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Idempotency-Key", "a".repeat(65))
                .content("""
                    {"type":"email","recipient":"user@test.com","content":"Body"}
                    """))
            .andExpect(status().isBadRequest());
    }

    // --- Wrong method ---

    @Test
    void wrongMethod_returns405() throws Exception {
        mockMvc.perform(delete("/api/notifications/1/retry"))
            .andExpect(status().isMethodNotAllowed());
    }
}
