package com.finalysis.api;

import com.finalysis.ai.AiProperties;
import com.finalysis.ai.AiStatus;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    /** @param ai the AI mode, for the UI's cloud banner (docs/plans/phase-4.md §7) */
    public record StatusResponse(String app, String status, Instant timestamp, AiStatus ai) {}

    private final AiStatus ai;

    public StatusController(AiProperties aiProperties) {
        this.ai = AiStatus.of(aiProperties);
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse("finalysis", "ok", Instant.now(), ai);
    }
}
