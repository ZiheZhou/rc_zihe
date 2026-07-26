package com.examine.api.dto;

import com.examine.domain.model.Status;

public record NotificationResponse(String requestId, Status status) {
}
