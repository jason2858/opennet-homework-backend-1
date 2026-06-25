package com.example.demo.model.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

public record EmailOptionsDto(
    @Size(max = 255) String fromAddress,
    @Size(max = 255) String replyTo,
    @Size(max = 50, message = "cc must not exceed 50 addresses") List<String> cc,
    @Size(max = 50, message = "bcc must not exceed 50 addresses") List<String> bcc,
    String contentType,
    @Size(max = 20, message = "attachmentUrls must not exceed 20 items") List<String> attachmentUrls
) {}
