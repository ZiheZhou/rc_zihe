package com.examine.api;

import com.examine.api.dto.CreateNotificationRequest;
import com.examine.api.dto.ErrorResponse;
import com.examine.api.dto.NotificationResponse;
import com.examine.api.dto.NotificationStatusResponse;
import com.examine.application.NotificationAcceptAppService;
import com.examine.application.NotificationNotFoundException;
import com.examine.domain.model.AcceptResult;
import com.examine.domain.model.Status;
import com.examine.domain.repository.NotificationRequestRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationAcceptAppService acceptAppService;
    private final NotificationRequestRepository requestRepository;

    public NotificationController(NotificationAcceptAppService acceptAppService,
                                  NotificationRequestRepository requestRepository) {
        this.acceptAppService = acceptAppService;
        this.requestRepository = requestRepository;
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody CreateNotificationRequest request) {
        AcceptResult result = acceptAppService.accept(
                request.vendorKey(), request.idempotencyKey(), request.payload());
        return switch (result) {
            case AcceptResult.Accepted accepted ->
                    ResponseEntity.status(HttpStatus.ACCEPTED)
                            .body(new NotificationResponse(accepted.requestId(), Status.PENDING));
            case AcceptResult.Duplicate duplicate ->
                    ResponseEntity.ok(new NotificationResponse(duplicate.requestId(), duplicate.status()));
            case AcceptResult.DeadLettered deadLettered ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(new ErrorResponse("ALREADY_DEAD_LETTERED",
                                    "idempotencyKey already delivered to dead letter, requestId="
                                            + deadLettered.requestId() + "; use admin replay"));
        };
    }

    @GetMapping("/{requestId}")
    public NotificationStatusResponse getById(@PathVariable String requestId) {
        return requestRepository.findById(requestId)
                .map(NotificationStatusResponse::from)
                .orElseThrow(() -> new NotificationNotFoundException(requestId));
    }
}
