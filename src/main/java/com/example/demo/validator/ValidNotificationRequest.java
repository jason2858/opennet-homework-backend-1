package com.example.demo.validator;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = NotificationRequestValidator.class)
public @interface ValidNotificationRequest {
    String message() default "Invalid notification request";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
}
