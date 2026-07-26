package com.examine.api;

import com.examine.application.VendorConfigAppService;
import com.examine.application.VendorConfigNotFoundException;
import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VendorConfigAdminController.class)
class VendorConfigAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VendorConfigAppService vendorConfigAppService;

    private static final String VALID_BODY = """
            {
              "vendorKey": "vendor-a",
              "endpoint": "https://api.vendor-a.com/notify",
              "method": "POST",
              "headers": {"Authorization": "Bearer token"},
              "bodyTemplate": "{\\"user\\":\\"{{userId}}\\"}",
              "timeoutMs": 30000,
              "retryPolicy": {"maxAttempts": 10, "baseDelayMs": 1000, "maxDelayMs": 3600000},
              "rateLimit": {"qps": 10, "burst": 20},
              "circuitBreaker": {"mode": "AUTO", "failureRateThreshold": 50, "minCalls": 10, "cooldownSeconds": 60, "halfOpenMaxCalls": 3},
              "idempotencyKeyLocation": "HEADER",
              "idempotencyKeyName": "Idempotency-Key"
            }
            """;

    private VendorConfig sampleConfig() {
        return new VendorConfig(
                "vendor-a", "https://api.vendor-a.com/notify", HttpMethod.POST,
                Map.of("Authorization", "Bearer token"), "{\"user\":\"{{userId}}\"}",
                Duration.ofSeconds(30),
                new RetryPolicySettings(10, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(10, 20),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                IdempotencyKeyLocation.HEADER, "Idempotency-Key");
    }

    @Test
    void createReturns200() throws Exception {
        when(vendorConfigAppService.create(any())).thenReturn(sampleConfig());

        mockMvc.perform(post("/admin/v1/vendor-configs")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorKey").value("vendor-a"));
    }

    @Test
    void getExistingReturns200() throws Exception {
        when(vendorConfigAppService.findByKey("vendor-a")).thenReturn(Optional.of(sampleConfig()));

        mockMvc.perform(get("/admin/v1/vendor-configs/vendor-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorKey").value("vendor-a"))
                .andExpect(jsonPath("$.endpoint").value("https://api.vendor-a.com/notify"));
    }

    @Test
    void getMissingReturns404() throws Exception {
        when(vendorConfigAppService.findByKey("missing")).thenReturn(Optional.empty());

        mockMvc.perform(get("/admin/v1/vendor-configs/missing"))
                .andExpect(status().isNotFound());
    }

    @Test
    void listReturnsAll() throws Exception {
        when(vendorConfigAppService.findAll()).thenReturn(List.of(sampleConfig()));

        mockMvc.perform(get("/admin/v1/vendor-configs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].vendorKey").value("vendor-a"));
    }

    @Test
    void updateExistingReturns200() throws Exception {
        when(vendorConfigAppService.update(eq("vendor-a"), any())).thenReturn(sampleConfig());

        mockMvc.perform(put("/admin/v1/vendor-configs/vendor-a")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isOk());
        verify(vendorConfigAppService).update(eq("vendor-a"), any());
    }

    @Test
    void updateMissingReturns404() throws Exception {
        when(vendorConfigAppService.update(eq("missing"), any()))
                .thenThrow(new VendorConfigNotFoundException("missing"));

        mockMvc.perform(put("/admin/v1/vendor-configs/missing")
                        .contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteReturns204() throws Exception {
        mockMvc.perform(delete("/admin/v1/vendor-configs/vendor-a"))
                .andExpect(status().isNoContent());
        verify(vendorConfigAppService).delete("vendor-a");
    }

    @Test
    void previewRendersTemplateWithoutHttpCall() throws Exception {
        when(vendorConfigAppService.preview(eq("vendor-a"), anyMap()))
                .thenReturn(new VendorHttpRequest(
                        "https://api.vendor-a.com/notify", HttpMethod.POST,
                        Map.of("Authorization", "Bearer token", "Idempotency-Key", "preview"),
                        "{\"user\":\"u1\"}", Duration.ofSeconds(30)));

        mockMvc.perform(post("/admin/v1/vendor-configs/vendor-a/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{\"userId\":\"u1\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("https://api.vendor-a.com/notify"))
                .andExpect(jsonPath("$.body").value("{\"user\":\"u1\"}"))
                .andExpect(jsonPath("$.headers.['Idempotency-Key']").value("preview"));
    }

    @Test
    void previewMissingConfigReturns404() throws Exception {
        when(vendorConfigAppService.preview(eq("missing"), anyMap()))
                .thenThrow(new VendorConfigNotFoundException("missing"));

        mockMvc.perform(post("/admin/v1/vendor-configs/missing/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"payload\":{}}"))
                .andExpect(status().isNotFound());
    }
}
