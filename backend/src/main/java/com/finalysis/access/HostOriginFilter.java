package com.finalysis.access;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Host and Origin checks (docs/plans/phase-8.md §9.3, §9.5, §16 decision 9), ahead of everything:
 *
 * <ul>
 *   <li>a {@code Host} other than {@code localhost}, {@code 127.0.0.1}, {@code ::1} or the open LAN address gets 421:
 *       a web page that rebinds its own name to 127.0.0.1 can't reach the API;</li>
 *   <li>on the LAN listener, every request but GET, HEAD and OPTIONS needs {@code Origin} to be the listener's own
 *       (with {@code SameSite=Strict} cookies, the CSRF defence);</li>
 *   <li>on localhost, such a request with an {@code Origin} that isn't the app's own (Vite in development, the backend
 *       itself) gets 403; tools that send none (curl, newman) still work.</li>
 * </ul>
 */
final class HostOriginFilter extends OncePerRequestFilter {

    private static final Set<String> LOCAL_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "[::1]");
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final LanListener listener;
    private final List<String> localOrigins;

    HostOriginFilter(LanListener listener, List<String> localOrigins) {
        this.listener = listener;
        this.localOrigins = localOrigins;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String host = request.getServerName();
        Optional<String> lanAddress = listener.address();
        boolean lan = listener.isLanRequest(request);
        boolean hostAllowed = lan ? lanAddress.map(host::equals).orElse(false) : LOCAL_HOSTS.contains(host);
        if (!hostAllowed) {
            refuse(response, 421, "host-refused", "Misdirected Request");
            return;
        }
        if (!SAFE_METHODS.contains(request.getMethod())) {
            String origin = request.getHeader("Origin");
            boolean originAllowed = lan
                    ? origin != null && listener.origin().map(origin::equals).orElse(false)
                    : origin == null || localOrigins.contains(origin) || isOwnLocalOrigin(origin, request);
            if (!originAllowed) {
                refuse(response, 403, "origin-refused", "Forbidden");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    /** The backend's own page, when it serves one on localhost: same scheme, a local host and the same port. */
    private static boolean isOwnLocalOrigin(String origin, HttpServletRequest request) {
        for (String host : List.of("localhost", "127.0.0.1")) {
            if (origin.equals("http://" + host + ":" + request.getLocalPort())) {
                return true;
            }
        }
        return false;
    }

    private static void refuse(HttpServletResponse response, int status, String code, String title)
            throws IOException {
        byte[] body = ("{\"type\":\"urn:finalysis:problem:" + code + "\",\"title\":\"" + title + "\",\"status\":"
                + status + "}").getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
