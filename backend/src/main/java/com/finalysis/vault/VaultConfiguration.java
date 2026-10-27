package com.finalysis.vault;

import com.zaxxer.hikari.HikariConfig;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Locked mode in one application context (docs/plans/phase-8.md §5.1, §16 decision 3): the app starts without a
 * database; {@link VaultDataSource} is the only data source; Flyway's migration waits for the database to open; the
 * gate runs ahead of every filter; the lifecycle opens the plain database at startup with the vault off, locks a vault
 * left mounted, and locks at shutdown.
 */
@Configuration(proxyBeanMethods = false)
class VaultConfiguration {

    @Bean
    @Primary
    VaultDataSource dataSource() {
        return new VaultDataSource();
    }

    /** {@code spring.datasource.hikari.*} for whichever pool opens: the vault's or the plain one. */
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariConfig vaultPoolSettings() {
        return new HikariConfig();
    }

    /** Flyway runs when the database opens ({@link VaultDatabase}), not at startup. */
    @Bean
    FlywayMigrationStrategy migrateWhenTheDatabaseOpens() {
        return flyway -> { };
    }

    /** The plain database for the vault off: a service connection in the tests, else {@code spring.datasource}. */
    @Bean
    PlainDatabaseConnection plainDatabaseConnection(ObjectProvider<JdbcConnectionDetails> details,
                                                    DataSourceProperties properties) {
        JdbcConnectionDetails connection = details.getIfAvailable();
        if (connection != null) {
            return new PlainDatabaseConnection(connection.getJdbcUrl(), connection.getUsername(),
                    connection.getPassword());
        }
        return new PlainDatabaseConnection(properties.determineUrl(), properties.determineUsername(),
                properties.determinePassword());
    }

    @Bean
    VaultClock vaultClock() {
        return VaultClock.SYSTEM;
    }

    @Bean
    KdfPolicy kdfPolicy() {
        return new KdfPolicy();
    }

    @Bean
    PasswordPolicy passwordPolicy() {
        return new PasswordPolicy();
    }

    @Bean
    VaultScripts vaultScripts(VaultProperties properties) {
        return new VaultScripts(properties.scriptsDir());
    }

    @Bean
    VaultMount vaultMount(VaultScripts scripts, VaultProperties properties) {
        return new HdiutilVaultMount(scripts, properties);
    }

    @Bean
    DatabaseRuntime databaseRuntime(VaultScripts scripts, VaultProperties properties) {
        return new NativeDatabaseRuntime(scripts, properties);
    }

    @Bean
    VaultRequests vaultRequests() {
        return new VaultRequests();
    }

    @Bean
    VaultDatabase vaultDatabase(VaultDataSource dataSource, HikariConfig vaultPoolSettings, Flyway flyway,
                                EntityManagerFactory entityManagerFactory) {
        return new VaultDatabase(dataSource, vaultPoolSettings, flyway, entityManagerFactory);
    }

    @Bean
    VaultService vaultService(VaultProperties properties, VaultMount mount, DatabaseRuntime runtime,
                              VaultDatabase database, KdfPolicy kdf, PasswordPolicy passwords, VaultClock clock,
                              VaultRequests requests, ApplicationEventPublisher events,
                              PlainDatabaseConnection plain) {
        return new VaultService(properties, mount, runtime, database, kdf, passwords, new UnlockAttempts(clock),
                requests, clock, events, plain);
    }

    @Bean
    FilterRegistrationBean<VaultGate> vaultGate(VaultService vault, VaultRequests requests) {
        FilterRegistrationBean<VaultGate> registration = new FilterRegistrationBean<>(new VaultGate(vault, requests));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("vaultGate");
        return registration;
    }

    @Bean
    VaultLifecycle vaultLifecycle(VaultService vault) {
        return new VaultLifecycle(vault);
    }

    @Bean
    VaultTicker vaultTicker(VaultService vault) {
        return new VaultTicker(vault);
    }

    /**
     * Opens or reads the vault when the context starts, before the web server takes requests (phase 0 starts before
     * the web server's); locks when it stops, after the web server has drained its requests.
     */
    static final class VaultLifecycle implements SmartLifecycle {

        private final VaultService vault;
        private volatile boolean running;

        VaultLifecycle(VaultService vault) {
            this.vault = vault;
        }

        @Override
        public void start() {
            vault.start();
            running = true;
        }

        @Override
        public void stop() {
            try {
                vault.shutdown();
            } finally {
                running = false;
            }
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return 0;
        }
    }

    /** The idle and wake locks' clock (§5.3): every 10 seconds. */
    static final class VaultTicker {

        private final VaultService vault;

        VaultTicker(VaultService vault) {
            this.vault = vault;
        }

        @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
        void tick() {
            vault.tick();
        }
    }
}
