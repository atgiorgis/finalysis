package com.finalysis.access;

import com.finalysis.vault.VaultClock;
import com.finalysis.vault.VaultFiles;
import com.finalysis.vault.VaultService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Phone access (docs/plans/phase-8.md §9, §16 decision 7): Spring Security with two filter chains. On the LAN listener a
 * logged-in session is needed for everything but the login, the session check and the UI's own files; on localhost
 * every request is let through as before (the vault gate still applies), with no session. The Host and Origin checks
 * come first; the built UI is served on the LAN listener only (development on localhost stays on Vite).
 */
@Configuration(proxyBeanMethods = false)
class AccessConfiguration {

    @Bean
    LanAddresses lanAddresses(AccessProperties properties) {
        return new LanAddresses(properties.loopbackForTests());
    }

    @Bean
    LanSettings lanSettings(JdbcTemplate jdbc, AccessProperties properties) {
        return new LanSettings(jdbc, properties.defaultPort());
    }

    @Bean
    LanSessions lanSessions() {
        return new LanSessions();
    }

    @Bean
    LanListener lanListener(LanSettings settings, LanAddresses addresses, LanSessions sessions, VaultFiles files) {
        return new LanListener(settings, addresses, sessions, files);
    }

    @Bean
    LanLogin lanLogin(VaultService vault, LanListener listener, VaultClock clock) {
        return new LanLogin(vault, listener, clock);
    }

    /** The phone listener may not take the app's own port, the vault database's or the Docker dev database's. */
    @Bean
    AccessController.ReservedPorts reservedPorts(@Value("${server.port:8080}") int serverPort,
                                                 @Value("${finalysis.vault.db-port:55432}") int vaultDbPort) {
        return new AccessController.ReservedPorts(Set.of(serverPort, vaultDbPort, 5432));
    }

    @Bean
    FilterRegistrationBean<HostOriginFilter> hostOriginFilter(LanListener listener) {
        FilterRegistrationBean<HostOriginFilter> registration = new FilterRegistrationBean<>(
                new HostOriginFilter(listener, List.of("http://localhost:5173", "http://127.0.0.1:5173")));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("hostOriginFilter");
        return registration;
    }

    /** No session, no login: a 401 problem the UI turns into its login screen. */
    private static final AuthenticationEntryPoint LOGIN_REQUIRED = (request, response, ex) -> {
        byte[] body = "{\"type\":\"urn:finalysis:problem:login-required\",\"title\":\"Login required\",\"status\":401}"
                .getBytes(StandardCharsets.UTF_8);
        response.setStatus(401);
        response.setContentType("application/problem+json");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(body);
    };

    @Bean
    @Order(1)
    SecurityFilterChain lanChain(HttpSecurity http, LanListener listener) throws Exception {
        // One statement per part: a single chained expression of nested lambdas sends javac's overload resolution into
        // minutes of work.
        RequestMatcher lan = listener::isLanRequest;
        RequestMatcher uiFiles = request -> !request.getRequestURI().startsWith("/api/")
                && !request.getRequestURI().startsWith("/actuator");
        http.securityMatcher(lan);
        http.authorizeHttpRequests(authorize -> {
            authorize.requestMatchers(HttpMethod.POST, "/api/access/login").permitAll();
            authorize.requestMatchers(HttpMethod.GET, "/api/access/session").permitAll();
            authorize.requestMatchers(uiFiles).permitAll();
            authorize.anyRequest().hasRole("PHONE");
        });
        http.exceptionHandling(handling -> handling.authenticationEntryPoint(LOGIN_REQUIRED));
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER));
        // SameSite=Strict cookies and HostOriginFilter's Origin check are the CSRF defence (§9.3).
        withoutDefaults(http);
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain localChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().permitAll());
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        withoutDefaults(http);
        return http.build();
    }

    /** No CSRF tokens, login pages, basic auth, logout endpoint or saved requests; our own Cache-Control (no-store). */
    private static void withoutDefaults(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable);
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        http.logout(AbstractHttpConfigurer::disable);
        http.requestCache(AbstractHttpConfigurer::disable);
        http.headers(headers -> headers.cacheControl(HeadersConfigurer.CacheControlConfig::disable));
    }

    /** The built UI (§9.4), on the LAN listener only; any path that isn't a file is the app's index.html. */
    @Bean
    WebMvcConfigurer lanUi(AccessProperties properties, LanListener listener) {
        String location = Path.of(properties.uiDir()).toAbsolutePath().normalize().toUri().toString();
        return new WebMvcConfigurer() {
            /** "/" itself: the resource handler skips an empty path, so it forwards to the index (LAN only, as below). */
            @Override
            public void addViewControllers(ViewControllerRegistry registry) {
                registry.addViewController("/").setViewName("forward:/index.html");
            }

            @Override
            public void addResourceHandlers(ResourceHandlerRegistry registry) {
                registry.addResourceHandler("/**").addResourceLocations(location).resourceChain(false)
                        .addResolver(new PathResourceResolver() {
                            @Override
                            protected Resource getResource(String path, Resource base) throws IOException {
                                if (!onLan(listener) || path.startsWith("api/") || path.startsWith("actuator")) {
                                    return null;
                                }
                                Resource found = super.getResource(path, base);
                                return found != null ? found : super.getResource("index.html", base);
                            }
                        });
            }
        };
    }

    private static boolean onLan(LanListener listener) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            return listener.isLanRequest(attributes.getRequest());
        }
        return false;
    }
}
