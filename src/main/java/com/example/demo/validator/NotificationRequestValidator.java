package com.example.demo.validator;

import com.example.demo.model.dto.EmailOptionsDto;
import com.example.demo.model.dto.request.NotificationRequest;
import com.example.demo.model.enums.NotificationType;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

public class NotificationRequestValidator implements ConstraintValidator<ValidNotificationRequest, NotificationRequest> {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern PHONE_PATTERN = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    @Override
    public boolean isValid(NotificationRequest req, ConstraintValidatorContext ctx) {
        if (req == null || req.type() == null) return true;

        ctx.disableDefaultConstraintViolation();
        boolean valid = true;

        if (NotificationType.EMAIL.name().equalsIgnoreCase(req.type())) {
            valid = validateEmailType(req, ctx);
        } else if (NotificationType.SMS.name().equalsIgnoreCase(req.type())) {
            valid = validateSmsType(req, ctx);
        }

        if (req.scheduledAt() != null && req.scheduledAt().isBefore(LocalDateTime.now())) {
            ctx.buildConstraintViolationWithTemplate("scheduledAt must be a future date/time")
                .addPropertyNode("scheduledAt").addConstraintViolation();
            valid = false;
        }

        return valid;
    }

    private boolean validateEmailType(NotificationRequest req, ConstraintValidatorContext ctx) {
        boolean valid = true;
        if (req.recipient() != null && !EMAIL_PATTERN.matcher(req.recipient()).matches()) {
            ctx.buildConstraintViolationWithTemplate("recipient must be a valid email address")
                .addPropertyNode("recipient").addConstraintViolation();
            valid = false;
        }
        EmailOptionsDto opts = req.emailOptions();
        if (opts != null) {
            valid &= validateEmailList(opts.cc(), "emailOptions.cc", ctx);
            valid &= validateEmailList(opts.bcc(), "emailOptions.bcc", ctx);
            if (opts.contentType() != null
                && !opts.contentType().equals("text/plain")
                && !opts.contentType().equals("text/html")) {
                ctx.buildConstraintViolationWithTemplate("contentType must be 'text/plain' or 'text/html'")
                    .addPropertyNode("emailOptions.contentType").addConstraintViolation();
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateSmsType(NotificationRequest req, ConstraintValidatorContext ctx) {
        boolean valid = true;
        if (req.recipient() != null && !PHONE_PATTERN.matcher(req.recipient()).matches()) {
            ctx.buildConstraintViolationWithTemplate("recipient must be a valid E.164 phone number (e.g. +886912345678)")
                .addPropertyNode("recipient").addConstraintViolation();
            valid = false;
        }
        if (req.content() != null) {
            boolean hasNonAscii = req.content().chars().anyMatch(c -> c > 127);
            int maxLen = hasNonAscii ? 70 : 160;
            if (req.content().length() > maxLen) {
                ctx.buildConstraintViolationWithTemplate(
                    "SMS content must not exceed " + maxLen + " characters" + (hasNonAscii ? " (Unicode detected)" : ""))
                    .addPropertyNode("content").addConstraintViolation();
                valid = false;
            }
        }
        return valid;
    }

    private boolean validateEmailList(List<String> emails, String field, ConstraintValidatorContext ctx) {
        if (emails == null) return true;
        boolean valid = true;
        for (String email : emails) {
            if (!EMAIL_PATTERN.matcher(email).matches()) {
                ctx.buildConstraintViolationWithTemplate("'" + email + "' is not a valid email address")
                    .addPropertyNode(field).addConstraintViolation();
                valid = false;
            }
        }
        return valid;
    }
}
