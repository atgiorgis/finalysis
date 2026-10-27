package com.finalysis.access;

import com.finalysis.vault.VaultClock;
import com.finalysis.vault.VaultService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;

/**
 * The phone's login (docs/plans/phase-8.md §9.3): the vault's own password, checked by unwrapping its key file (there
 * is no second password store). Per client address, after five wrong passwords each attempt waits, 30 s doubling to 15
 * minutes; 20 wrong passwords in an hour, from anywhere, close the listener until the next unlock on the computer. A
 * success gives a new session (a new id, so a planted one is useless) that lasts the auto-lock minutes idle.
 */
class LanLogin {

    private static final Logger log = LoggerFactory.getLogger(LanLogin.class);
    static final int FREE_ATTEMPTS = 5;
    static final Duration FIRST_WAIT = Duration.ofSeconds(30);
    static final Duration MAX_WAIT = Duration.ofMinutes(15);
    static final int HOURLY_LIMIT = 20;

    private final VaultService vault;
    private final LanListener listener;
    private final VaultClock clock;
    private final Map<String, Attempts> byAddress = new ConcurrentHashMap<>();
    private final Deque<Instant> lastHour = new ArrayDeque<>();

    private static final class Attempts {
        int failures;
        Instant blockedUntil = Instant.MIN;
    }

    LanLogin(VaultService vault, LanListener listener, VaultClock clock) {
        this.vault = vault;
        this.listener = listener;
        this.clock = clock;
    }

    /** Logs the phone in, or throws a problem; never says more than "wrong password". */
    void login(HttpServletRequest request, String password) {
        String address = request.getRemoteAddr();
        Attempts attempts = byAddress.computeIfAbsent(address, a -> new Attempts());
        synchronized (attempts) {
            Instant now = clock.wall();
            if (now.isBefore(attempts.blockedUntil)) {
                throw AccessException.tooManyAttempts(Duration.between(now, attempts.blockedUntil));
            }
        }
        if (!vault.verifyPassword(password)) {
            failed(attempts);
            throw AccessException.wrongPassword();
        }
        synchronized (attempts) {
            attempts.failures = 0;
            attempts.blockedUntil = Instant.MIN;
        }
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate();
        }
        HttpSession session = request.getSession(true);
        session.setMaxInactiveInterval(vault.idleLockMinutes() * 60);
        SecurityContext context = new SecurityContextImpl(new UsernamePasswordAuthenticationToken("phone", null,
                List.of(new SimpleGrantedAuthority("ROLE_PHONE"))));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        log.info("Phone logged in");
    }

    private void failed(Attempts attempts) {
        Instant now = clock.wall();
        synchronized (attempts) {
            attempts.failures++;
            if (attempts.failures >= FREE_ATTEMPTS) {
                int doublings = Math.min(attempts.failures - FREE_ATTEMPTS, 10);
                Duration wait = FIRST_WAIT.multipliedBy(1L << doublings);
                attempts.blockedUntil = now.plus(wait.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : wait);
            }
        }
        boolean tooMany;
        synchronized (lastHour) {
            lastHour.addLast(now);
            while (!lastHour.isEmpty() && lastHour.peekFirst().isBefore(now.minus(Duration.ofHours(1)))) {
                lastHour.removeFirst();
            }
            tooMany = lastHour.size() >= HOURLY_LIMIT;
            if (tooMany) {
                lastHour.clear();
            }
        }
        log.warn("Phone login failed");
        if (tooMany) {
            listener.tooManyFailures();
        }
    }
}
