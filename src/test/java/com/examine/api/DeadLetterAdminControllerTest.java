package com.examine.api;

import com.examine.api.dto.NotificationResponse;
import com.examine.api.dto.NotificationStatusResponse;
import com.examine.application.DeadLetterReplayAppService;
import com.examine.application.NotificationNotFoundException;
import com.examine.domain.model.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeadLetterAdminController.class)
class DeadLetterAdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeadLetterReplayAppService replayAppService;

    @Test
    void replayDeadLetterReturns200() throws Exception {
        when(replayAppService.replay("req-1"))
                .thenReturn(new NotificationResponse("req-1", Status.PENDING));

        mockMvc.perform(post("/admin/v1/dead-letters/req-1/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void replayMissingReturns404() throws Exception {
        when(replayAppService.replay("missing"))
                .thenThrow(new NotificationNotFoundException("missing"));

        mockMvc.perform(post("/admin/v1/dead-letters/missing/retry"))
                .andExpect(status().isNotFound());
    }

    @Test
    void replayNonDeadLetterReturns409() throws Exception {
        when(replayAppService.replay("req-1"))
                .thenThrow(new IllegalStateException("only DEAD_LETTERED can be replayed, current: SUCCESS"));

        mockMvc.perform(post("/admin/v1/dead-letters/req-1/retry"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void listDeadLettersReturns200() throws Exception {
        when(replayAppService.listDeadLetters(100)).thenReturn(List.of(
                new NotificationStatusResponse("req-1", "vendor-a", Status.DEAD_LETTERED,
                        3, Instant.parse("2026-07-26T10:00:00Z"), null, "client error: 400",
                        Instant.parse("2026-07-26T09:00:00Z"), Instant.parse("2026-07-26T10:00:00Z"))));

        mockMvc.perform(get("/admin/v1/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestId").value("req-1"))
                .andExpect(jsonPath("$[0].status").value("DEAD_LETTERED"))
                .andExpect(jsonPath("$[0].lastError").value("client error: 400"));
    }
}
