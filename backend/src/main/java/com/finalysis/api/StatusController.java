package com.finalysis.api;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    public record StatusResponse(String app, String status, Instant timestamp) {}

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse("finalysis", "ok", Instant.now());
    }
}
