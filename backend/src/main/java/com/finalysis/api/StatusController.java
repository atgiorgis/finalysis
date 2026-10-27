package com.finalysis.api;

import com.finalysis.ai.AiProperties;
import com.finalysis.ai.AiStatus;
import com.finalysis.vault.VaultService;
import com.finalysis.vault.VaultStatus;
import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class StatusController {

    /**
     * @param ai    the AI mode, for the UI's cloud banner (docs/plans/phase-4.md §7)
     * @param vault the vault's state (docs/plans/phase-8.md §5.2); answered while locked, never a path
     */
    public record StatusResponse(String app, String status, Instant timestamp, AiStatus ai, VaultStatus vault) {}

    private final AiStatus ai;
    private final VaultService vault;

    public StatusController(AiProperties aiProperties, VaultService vault) {
        this.ai = AiStatus.of(aiProperties);
        this.vault = vault;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse("finalysis", "ok", Instant.now(), ai, vault.status());
    }
}
