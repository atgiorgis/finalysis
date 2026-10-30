package com.finalysis.ai.selfhost;

import com.finalysis.ai.AiPathSettings;
import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Corgi → Self-hosted (docs/plans/phase-9.md §5.5). Like every endpoint, these need an unlocked vault (423
 * otherwise) and, from the phone, a logged-in session (§16 decision 9).
 *
 * <ul>
 *   <li>{@code GET /api/selfhost}: the server's state, progress, running time and estimate, or why it can't be read.</li>
 *   <li>{@code POST /api/selfhost/start} and {@code /stop}: 202 with the state after the request; 409
 *       {@code selfhost-unavailable} with the reason when they can't be done (no aws profile, no region, no server).</li>
 *   <li>{@code POST /api/selfhost/test} {@code {name}}: Ollama's version on that Tailscale name, or the reason. The name
 *       is checked as Settings stores it (letters, digits, hyphens, dots; never a URL).</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/selfhost")
class SelfHostController {

    record TestRequest(String name) {
    }

    private final SelfHostControl control;

    SelfHostController(SelfHostControl control) {
        this.control = control;
    }

    @GetMapping
    SelfHostStatus status() {
        return control.status();
    }

    @PostMapping("/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SelfHostStatus start() {
        return control.start();
    }

    @PostMapping("/stop")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SelfHostStatus stop() {
        return control.stop();
    }

    @PostMapping("/test")
    SelfHostControl.TestResult test(@RequestBody TestRequest request) {
        String name = request.name() == null ? null : request.name().strip();
        if (!AiPathSettings.validServerName(name)) {
            throw new InvalidRequestException(List.of(new InvalidField("name",
                    "must be a Tailscale name: letters, digits, hyphens and dots, never a URL")));
        }
        return control.test(name);
    }
}
