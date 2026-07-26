package com.examine.domain.service;

import com.examine.domain.model.VendorHttpRequest;
import com.examine.domain.model.config.CircuitBreakerMode;
import com.examine.domain.model.config.CircuitBreakerSettings;
import com.examine.domain.model.config.HttpMethod;
import com.examine.domain.model.config.IdempotencyKeyLocation;
import com.examine.domain.model.config.RateLimitSettings;
import com.examine.domain.model.config.RetryPolicySettings;
import com.examine.domain.model.config.VendorConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VendorRequestAssemblerTest {

    private final VendorRequestAssembler assembler = new VendorRequestAssembler();

    private VendorConfig config(String bodyTemplate, IdempotencyKeyLocation keyLocation, Map<String, String> headers) {
        return new VendorConfig(
                "vendor-a",
                "https://api.vendor-a.com/notify",
                HttpMethod.POST,
                headers,
                bodyTemplate,
                Duration.ofSeconds(30),
                new RetryPolicySettings(10, Duration.ofSeconds(1), Duration.ofHours(1)),
                new RateLimitSettings(10, 20),
                new CircuitBreakerSettings(CircuitBreakerMode.AUTO, 50, 10, 60, 3),
                keyLocation,
                "Idempotency-Key");
    }

    @Test
    void rendersBodyTemplateWithPayloadValues() {
        VendorConfig config = config("{\"user\":\"{{userId}}\",\"msg\":\"{{msg}}\"}",
                IdempotencyKeyLocation.HEADER, Map.of());

        VendorHttpRequest request = assembler.assemble("req-1", "idem-1",
                Map.of("userId", "u1", "msg", "hello"), config, 1);

        assertEquals("https://api.vendor-a.com/notify", request.url());
        assertEquals(HttpMethod.POST, request.method());
        assertEquals("{\"user\":\"u1\",\"msg\":\"hello\"}", request.body());
        assertEquals(Duration.ofSeconds(30), request.timeout());
    }

    @Test
    void missingPlaceholderRendersAsEmptyString() {
        VendorConfig config = config("{\"user\":\"{{userId}}\",\"msg\":\"{{missing}}\"}",
                IdempotencyKeyLocation.HEADER, Map.of());

        VendorHttpRequest request = assembler.assemble("req-1", "idem-1",
                Map.of("userId", "u1"), config, 1);

        assertEquals("{\"user\":\"u1\",\"msg\":\"\"}", request.body());
    }

    @Test
    void headerModeInjectsIdempotencyKeyIntoHeaders() {
        VendorConfig config = config("{\"msg\":\"{{msg}}\"}",
                IdempotencyKeyLocation.HEADER, Map.of("Authorization", "Bearer token"));

        VendorHttpRequest request = assembler.assemble("req-1", "idem-1",
                Map.of("msg", "hi"), config, 1);

        assertEquals("idem-1", request.headers().get("Idempotency-Key"));
        assertEquals("Bearer token", request.headers().get("Authorization"));
    }

    @Test
    void bodyModeDoesNotAddHeaderButTemplateCanUseIdempotencyKey() {
        VendorConfig config = config("{\"key\":\"{{idempotencyKey}}\",\"req\":\"{{requestId}}\"}",
                IdempotencyKeyLocation.BODY, Map.of("Authorization", "Bearer token"));

        VendorHttpRequest request = assembler.assemble("req-1", "idem-1",
                Map.of("msg", "hi"), config, 1);

        assertEquals("{\"key\":\"idem-1\",\"req\":\"req-1\"}", request.body());
        assertFalse(request.headers().containsKey("Idempotency-Key"));
    }

    @Test
    void headerValuesAreAlsoRendered() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-Tenant", "{{tenant}}");
        VendorConfig config = config("{}", IdempotencyKeyLocation.HEADER, headers);

        VendorHttpRequest request = assembler.assemble("req-1", "idem-1",
                Map.of("tenant", "t-42"), config, 1);

        assertEquals("t-42", request.headers().get("X-Tenant"));
    }

    @Test
    void nullBodyTemplateProducesNullBody() {
        VendorConfig config = config(null, IdempotencyKeyLocation.HEADER, Map.of());

        VendorHttpRequest request = assembler.assemble("req-1", "idem-1", Map.of(), config, 1);

        assertNull(request.body());
    }
}
