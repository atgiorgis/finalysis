package com.finalysis.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every {@code /api} response says {@code Cache-Control: no-store} (docs/plans/phase-8.md §8.2, SEC-12), so no API data
 * is written to a browser's disk cache, on the desktop or a phone. Set before the handler runs, so error responses
 * carry it too; a handler that sets its own (also no-store) keeps it.
 */
@Configuration(proxyBeanMethods = false)
public class CacheControlConfiguration {

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> noStoreFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain chain) throws ServletException, IOException {
                response.setHeader("Cache-Control", "no-store");
                chain.doFilter(request, response);
            }

            @Override
            protected boolean shouldNotFilter(HttpServletRequest request) {
                return !request.getRequestURI().substring(request.getContextPath().length()).startsWith("/api/");
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/*");
        // Right after the vault gate, which answers its own 423s with no-store.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
        registration.setName("noStoreFilter");
        return registration;
    }
}
