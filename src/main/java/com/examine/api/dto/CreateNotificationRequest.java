package com.examine.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateNotificationRequest(
        @NotBlank String vendorKey,
        @NotBlank String idempotencyKey,
        @NotNull Map<String, Object> payload) {
}
