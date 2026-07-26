package com.examine.domain.model;

public sealed interface AcceptResult {
    record Accepted(String requestId) implements AcceptResult {}
    record Duplicate(String requestId, Status status) implements AcceptResult {}
    record DeadLettered(String requestId) implements AcceptResult {}
}
