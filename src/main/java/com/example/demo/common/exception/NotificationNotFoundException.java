package com.example.demo.common.exception;

import com.example.demo.constants.ErrorMessage;

public class NotificationNotFoundException extends ApiException {

    public NotificationNotFoundException(Long id) {
        super(ErrorCode.NOTIFICATION_NOT_FOUND, ErrorMessage.NOTIFICATION_NOT_FOUND + id);
    }
}
