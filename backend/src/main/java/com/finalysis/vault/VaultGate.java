package com.finalysis.vault;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The locked-mode gate (docs/plans/phase-8.md §5.1, §5.2), ahead of every other filter: unless the vault is unlocked,
 * every request gets 423 with a {@code vault-locked} problem and no detail, except the allowlist. While unlocked it
 * counts gated requests in flight (a lock waits for them) and counts them as activity for the idle lock. A request
 * after the machine slept locks first and is refused.
 */
final class VaultGate extends OncePerRequestFilter {

    /** Always admitted, never counted as activity. Setup, unlock, recovery and lock check the state themselves. */
    static final Set<String> ALLOWED = Set.of(
            "GET /api/status",
            "POST /api/vault/setup",
            "POST /api/vault/unlock",
            "POST /api/vault/recover",
            "POST /api/vault/lock",
            "GET /actuator/health");

    private static final byte[] LOCKED = """
            {"type":"urn:finalysis:problem:vault-locked","title":"Locked","status":423}"""
            .getBytes(StandardCharsets.UTF_8);

    private final VaultService vault;
    private final VaultRequests requests;

    VaultGate(VaultService vault, VaultRequests requests) {
        this.vault = vault;
        this.requests = requests;
    }

    static boolean allowed(String method, String path) {
        return ALLOWED.contains(method + " " + path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (allowed(request.getMethod(), path)) {
            chain.doFilter(request, response);
            return;
        }
        if (!vault.enabled()) {
            chain.doFilter(request, response);
            return;
        }
        if (vault.lockIfSlept() || vault.state() != VaultState.UNLOCKED) {
            refuse(response);
            return;
        }
        // The vault's own gated endpoints (password, activity) take the transition lock; a lock waiting for them
        // would wait on itself, so they are not counted as in flight.
        boolean counted = !path.startsWith("/api/vault/");
        if (counted) {
            requests.enter();
        }
        try {
            if (vault.state() != VaultState.UNLOCKED) { // a lock began between the check and enter()
                refuse(response);
                return;
            }
            vault.touch();
            chain.doFilter(request, response);
        } finally {
            if (counted) {
                requests.exit();
            }
        }
    }

    private static void refuse(HttpServletResponse response) throws IOException {
        response.setStatus(423);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(LOCKED.length);
        response.getOutputStream().write(LOCKED);
    }
}
