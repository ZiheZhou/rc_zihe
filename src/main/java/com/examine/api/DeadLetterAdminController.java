package com.examine.api;

import com.examine.api.dto.NotificationResponse;
import com.examine.api.dto.NotificationStatusResponse;
import com.examine.application.DeadLetterReplayAppService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/v1/dead-letters")
public class DeadLetterAdminController {

    private final DeadLetterReplayAppService replayAppService;

    public DeadLetterAdminController(DeadLetterReplayAppService replayAppService) {
        this.replayAppService = replayAppService;
    }

    @GetMapping
    public List<NotificationStatusResponse> list(@RequestParam(defaultValue = "100") int limit) {
        return replayAppService.listDeadLetters(limit);
    }

    @PostMapping("/{requestId}/retry")
    public NotificationResponse retry(@PathVariable String requestId) {
        return replayAppService.replay(requestId);
    }
}
