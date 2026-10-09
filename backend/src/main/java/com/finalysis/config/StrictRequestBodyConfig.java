package com.finalysis.config;

import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Rejects unknown fields in incoming request bodies, so a PATCH that tries to change anything
 * but the name fails instead of being silently ignored.
 *
 * <p>Only the server-side MVC converter is strict. The shared {@link JsonMapper} bean stays
 * lenient because Spring AI and HTTP clients use it to read provider responses, which may add
 * fields at any time.
 */
@Configuration(proxyBeanMethods = false)
public class StrictRequestBodyConfig {

    // Runs after Boot's Jackson customizer (order 0), so this JSON converter replaces Boot's.
    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ServerHttpMessageConvertersCustomizer strictRequestBodies(JsonMapper jsonMapper) {
        JsonMapper strict = jsonMapper.rebuild()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return builder -> builder.withJsonConverter(new JacksonJsonHttpMessageConverter(strict));
    }
}
