package com.examine.api;

import com.examine.application.NotificationAcceptAppService;
import com.examine.application.VendorNotFoundException;
import com.examine.domain.model.AcceptResult;
import com.examine.domain.model.NotificationRequest;
import com.examine.domain.model.Status;
import com.examine.domain.repository.NotificationRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NotificationController.class)
class NotificationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private NotificationAcceptAppService acceptAppService;

    @MockBean
    private NotificationRequestRepository requestRepository;

    private static final String VALID_BODY = """
            {"vendorKey":"vendor-a","idempotencyKey":"idem-1","payload":{"userId":"u1","msg":"hi"}}
            """;

    @Test
    void postNewNotificationReturns202() throws Exception {
        when(acceptAppService.accept(eq("vendor-a"), eq("idem-1"), anyMap()))
                .thenReturn(new AcceptResult.Accepted("req-1"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void postDuplicateReturns200WithCurrentStatus() throws Exception {
        when(acceptAppService.accept(anyString(), anyString(), anyMap()))
                .thenReturn(new AcceptResult.Duplicate("req-1", Status.SUCCESS));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void postDeadLetteredDuplicateReturns409() throws Exception {
        when(acceptAppService.accept(anyString(), anyString(), anyMap()))
                .thenReturn(new AcceptResult.DeadLettered("req-1"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_DEAD_LETTERED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("req-1")));
    }

    @Test
    void postMissingIdempotencyKeyReturns400() throws Exception {
        String body = """
                {"vendorKey":"vendor-a","payload":{"msg":"hi"}}
                """;

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void postUnknownVendorReturns400() throws Exception {
        when(acceptAppService.accept(anyString(), anyString(), anyMap()))
                .thenThrow(new VendorNotFoundException("vendor-x"));

        mockMvc.perform(post("/api/v1/notifications")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VENDOR_NOT_FOUND"));
    }

    @Test
    void getExistingNotificationReturns200() throws Exception {
        NotificationRequest request = NotificationRequest.create(
                "req-1", "vendor-a", "idem-1", "{}", Instant.parse("2026-07-26T10:00:00Z"));
        when(requestRepository.findById("req-1")).thenReturn(Optional.of(request));

        mockMvc.perform(get("/api/v1/notifications/req-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attemptCount").value(0));
    }

    @Test
    void getMissingNotificationReturns404() throws Exception {
        when(requestRepository.findById("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/notifications/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
