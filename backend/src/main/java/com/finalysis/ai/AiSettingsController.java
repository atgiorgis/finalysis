package com.finalysis.ai;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.util.List;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code PUT /api/settings/ai} (docs/plans/phase-9.md §3.1): switches the path without a restart. Like every settings
 * write it needs an unlocked vault (423 otherwise, from the vault's gate). It refuses a path that isn't available and
 * Self-hosted without a valid server name (400); it waits up to 60 seconds for running AI work, then answers 409
 * {@code ai-busy}. The answer is the status block after the switch.
 */
@RestController
@RequestMapping("/api/settings")
class AiSettingsController {

    /**
     * @param path           OFF, CLOUD, SELF_HOSTED or LOCAL
     * @param selfhostName   the server's Tailscale name (letters, digits, hyphens, dots); kept from before when absent
     * @param selfhostRegion its AWS region id, stored for later steps
     */
    record AiSettingsRequest(AiPath path, String selfhostName, String selfhostRegion) {
    }

    private final AiProviders providers;

    AiSettingsController(AiProviders providers) {
        this.providers = providers;
    }

    @PutMapping("/ai")
    AiStatus update(@RequestBody AiSettingsRequest request) {
        try {
            providers.switchTo(new AiPathSettings.Selection(request.path(), blankToNull(request.selfhostName()),
                    blankToNull(request.selfhostRegion())), AiProviders.SWITCH_WAIT);
        } catch (AiProviders.InvalidSelectionException e) {
            throw new InvalidRequestException(List.of(new InvalidField(e.field(), e.getMessage())));
        }
        return AiStatus.of(providers);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
