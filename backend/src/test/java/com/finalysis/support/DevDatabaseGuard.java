package com.finalysis.support;

import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Fails any test context whose datasource points at the dev database from compose.yaml.
 *
 * <p>Registered for every Spring test context in {@code META-INF/spring.factories}, so it also
 * catches a test that uses a bare {@code @SpringBootTest} instead of {@link IntegrationTest}.
 */
public class DevDatabaseGuard implements ContextCustomizerFactory {

    static final Pattern DEV_DATABASE_URL =
            Pattern.compile("jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1]):5432/finalysis\\b.*");

    @Override
    public ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        return new Customizer();
    }

    static void check(String jdbcUrl) {
        if (jdbcUrl != null && DEV_DATABASE_URL.matcher(jdbcUrl).matches()) {
            throw new IllegalStateException("Test datasource points at the dev database (" + jdbcUrl
                    + "). Annotate the test with @IntegrationTest so it uses Testcontainers.");
        }
    }

    // A record, so equal customizers keep Spring's test context cache working.
    record Customizer() implements ContextCustomizer {

        @Override
        public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration config) {
            context.getBeanFactory().addBeanPostProcessor(new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof HikariDataSource dataSource) {
                        check(dataSource.getJdbcUrl());
                    }
                    return bean;
                }
            });
        }
    }
}
