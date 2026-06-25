package com.example.demo.validator;

import com.example.demo.model.dto.EmailOptionsDto;
import com.example.demo.model.dto.SmsOptionsDto;
import com.example.demo.model.dto.request.NotificationRequest;
import jakarta.validation.ConstraintValidatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationRequestValidatorTest {

    private NotificationRequestValidator validator;

    @Mock private ConstraintValidatorContext ctx;
    @Mock private ConstraintValidatorContext.ConstraintViolationBuilder violationBuilder;
    @Mock private ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext nodeBuilder;

    @BeforeEach
    void setUp() {
        validator = new NotificationRequestValidator();
        when(ctx.buildConstraintViolationWithTemplate(anyString())).thenReturn(violationBuilder);
        when(violationBuilder.addPropertyNode(anyString())).thenReturn(nodeBuilder);
        when(nodeBuilder.addConstraintViolation()).thenReturn(ctx);
    }

    private NotificationRequest emailRequest(String recipient) {
        return new NotificationRequest("email", recipient, "Subject", "Content", null, null, null);
    }

    private NotificationRequest smsRequest(String recipient, String content) {
        return new NotificationRequest("sms", recipient, null, content, null, null, null);
    }

    // --- email type ---

    @Test
    void email_validRecipient_passes() {
        assertThat(validator.isValid(emailRequest("user@example.com"), ctx)).isTrue();
    }

    @Test
    void email_invalidRecipient_fails() {
        assertThat(validator.isValid(emailRequest("not-an-email"), ctx)).isFalse();
    }

    @Test
    void email_validEmailOptions_passes() {
        EmailOptionsDto opts = new EmailOptionsDto("from@x.com", null,
            List.of("cc@x.com"), List.of(), "text/html", List.of());
        NotificationRequest req = new NotificationRequest("email", "user@example.com",
            "S", "C", null, opts, null);
        assertThat(validator.isValid(req, ctx)).isTrue();
    }

    @Test
    void email_invalidCcAddress_fails() {
        EmailOptionsDto opts = new EmailOptionsDto(null, null,
            List.of("not-an-email"), null, null, null);
        NotificationRequest req = new NotificationRequest("email", "user@example.com",
            "S", "C", null, opts, null);
        assertThat(validator.isValid(req, ctx)).isFalse();
    }

    @Test
    void email_invalidContentType_fails() {
        EmailOptionsDto opts = new EmailOptionsDto(null, null,
            null, null, "text/xml", null);
        NotificationRequest req = new NotificationRequest("email", "user@example.com",
            "S", "C", null, opts, null);
        assertThat(validator.isValid(req, ctx)).isFalse();
    }

    // --- sms type ---

    @Test
    void sms_validE164Phone_passes() {
        assertThat(validator.isValid(smsRequest("+886912345678", "Hello"), ctx)).isTrue();
    }

    @Test
    void sms_phoneWithoutPlus_fails() {
        assertThat(validator.isValid(smsRequest("0912345678", "Hello"), ctx)).isFalse();
    }

    @Test
    void sms_asciiContentWithinLimit_passes() {
        assertThat(validator.isValid(smsRequest("+886912345678", "A".repeat(160)), ctx)).isTrue();
    }

    @Test
    void sms_asciiContentTooLong_fails() {
        assertThat(validator.isValid(smsRequest("+886912345678", "A".repeat(161)), ctx)).isFalse();
    }

    @Test
    void sms_unicodeContentWithinLimit_passes() {
        assertThat(validator.isValid(smsRequest("+886912345678", "中".repeat(70)), ctx)).isTrue();
    }

    @Test
    void sms_unicodeContentTooLong_fails() {
        assertThat(validator.isValid(smsRequest("+886912345678", "中".repeat(71)), ctx)).isFalse();
    }

    // --- scheduledAt ---

    @Test
    void scheduledAt_futureTime_passes() {
        NotificationRequest req = new NotificationRequest("email", "user@example.com",
            "S", "C", LocalDateTime.now().plusHours(1), null, null);
        assertThat(validator.isValid(req, ctx)).isTrue();
    }

    @Test
    void scheduledAt_pastTime_fails() {
        NotificationRequest req = new NotificationRequest("email", "user@example.com",
            "S", "C", LocalDateTime.now().minusHours(1), null, null);
        assertThat(validator.isValid(req, ctx)).isFalse();
    }

    // --- edge cases ---

    @Test
    void nullType_returnsValid() {
        NotificationRequest req = new NotificationRequest(null, "user@example.com",
            "S", "C", null, null, null);
        assertThat(validator.isValid(req, ctx)).isTrue();
    }

    @Test
    void nullRequest_returnsValid() {
        assertThat(validator.isValid(null, ctx)).isTrue();
    }
}
