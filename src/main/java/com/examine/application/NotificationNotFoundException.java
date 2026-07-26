package com.examine.application;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(String requestId) {
        super("Notification not found: " + requestId);
    }
}
