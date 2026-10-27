package com.finalysis.access;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Phone access (docs/plans/phase-8.md §9): the setting and the CA from the computer only; the login on the LAN listener
 * only. Logout from the phone is the vault's Lock ({@code POST /api/vault/lock}), for the whole app (§9.3).
 */
@RestController
class AccessController {

    record SettingRequest(Boolean enabled, String address, Integer port) {
    }

    record LoginRequest(String password) {
        @Override
        public String toString() {
            return "LoginRequest[hidden]";
        }
    }

    record Session(boolean authenticated, boolean lan) {
    }

    /** Ports the phone listener may not take: the app's own and the vault database's are refused too. */
    private final Set<Integer> reserved;
    private final LanListener listener;
    private final LanLogin login;
    private final LanSettings settings;
    private final LanAddresses addresses;

    AccessController(LanListener listener, LanLogin login, LanSettings settings, LanAddresses addresses,
                     ReservedPorts reserved) {
        this.listener = listener;
        this.login = login;
        this.settings = settings;
        this.addresses = addresses;
        this.reserved = reserved.ports();
    }

    record ReservedPorts(Set<Integer> ports) {
    }

    @GetMapping("/api/access")
    LanListener.Status status(HttpServletRequest request) {
        localOnly(request);
        return listener.status();
    }

    /** Turns phone access on or off; on, it needs one of this machine's private addresses and a free port. */
    @PutMapping("/api/access")
    LanListener.Status save(HttpServletRequest request, @RequestBody SettingRequest body) {
        localOnly(request);
        boolean enabled = Boolean.TRUE.equals(body.enabled());
        LanSettings.Setting current = settings.read();
        int port = body.port() == null ? current.port() : body.port();
        String address = body.address() == null ? current.address() : body.address();
        if (port < 1024 || port > 65535 || reserved.contains(port) || port == request.getLocalPort()) {
            throw AccessException.settingInvalid("Choose a port from 1024 to 65535 that Finalysis doesn't already use.");
        }
        if (enabled && (addresses.range(address).isEmpty() || !addresses.available().contains(address))) {
            throw AccessException.settingInvalid("Choose one of this computer's home-network addresses.");
        }
        settings.save(new LanSettings.Setting(enabled, address, port));
        listener.open();
        return listener.status();
    }

    /** The local CA, to send to the phone and install (§9.2); compare its fingerprint there. */
    @GetMapping("/api/access/ca.cer")
    ResponseEntity<byte[]> caCertificate(HttpServletRequest request) {
        localOnly(request);
        byte[] der = listener.caCertificate().orElseThrow(AccessException::noCertificate);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/pkix-cert"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("finalysis-local-ca.cer").build().toString())
                .cacheControl(CacheControl.noStore()).body(der);
    }

    @PostMapping("/api/access/login")
    ResponseEntity<Void> login(HttpServletRequest request, @RequestBody LoginRequest body) {
        if (!listener.isLanRequest(request)) {
            throw AccessException.lanOnly();
        }
        login.login(request, body.password());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    /** Whether this browser is logged in; open to anyone, says nothing else. */
    @GetMapping("/api/access/session")
    Session session(HttpServletRequest request) {
        boolean lan = listener.isLanRequest(request);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = !lan || (authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream().anyMatch(a -> "ROLE_PHONE".equals(a.getAuthority())));
        return new Session(authenticated, lan);
    }

    private void localOnly(HttpServletRequest request) {
        if (listener.isLanRequest(request)) {
            throw AccessException.localOnly();
        }
    }

    @RestControllerAdvice(assignableTypes = AccessController.class)
    @Order(Ordered.HIGHEST_PRECEDENCE)
    static class Problems {

        @ExceptionHandler(AccessException.class)
        ResponseEntity<ProblemDetail> handle(AccessException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
            problem.setType(URI.create("urn:finalysis:problem:" + ex.code()));
            ResponseEntity.BodyBuilder response = ResponseEntity.status(ex.status()).cacheControl(CacheControl.noStore());
            if (ex.retryAfter() != null) {
                response.header(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1, ex.retryAfter().toSeconds())));
            }
            return response.body(problem);
        }

        @ExceptionHandler(HttpMessageNotReadableException.class)
        ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException ex) {
            ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed request body");
            problem.setType(URI.create("urn:finalysis:problem:invalid-request"));
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(problem);
        }
    }
}
