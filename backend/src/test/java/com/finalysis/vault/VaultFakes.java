package com.finalysis.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.event.EventListener;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Stand-ins for the vault's machine-facing parts (docs/plans/phase-8.md §5.5, §10): a disk image held in memory (its
 * "mount point" a temporary folder), a database runtime that opens a scratch database in the Testcontainers Postgres,
 * a clock the test moves, and a cheap Argon2 policy. The vault's state machine, key file, gate and endpoints are real.
 */
final class VaultFakes {

    static final String SCRATCH_DATABASE = "vault_lifecycle";

    private VaultFakes() {
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        @Primary
        FakeVaultMount fakeVaultMount(VaultProperties properties) {
            return new FakeVaultMount(properties.mountPoint());
        }

        @Bean
        @Primary
        FakeDatabaseRuntime fakeDatabaseRuntime(PostgreSQLContainer postgres) {
            return new FakeDatabaseRuntime(postgres);
        }

        @Bean
        @Primary
        MutableVaultClock mutableVaultClock() {
            return new MutableVaultClock();
        }

        @Bean
        @Primary
        KdfPolicy cheapKdfPolicy() {
            return cheapKdf();
        }

        @Bean
        VaultEvents vaultEvents() {
            return new VaultEvents();
        }
    }

    /** Argon2id at 8 MiB and one pass: fast, and never calibrated upwards. */
    static KdfPolicy cheapKdf() {
        return new KdfPolicy(new KdfParameters(8 * 1024, 1, 1)) {
            @Override
            KdfParameters calibrate() {
                return floor();
            }
        };
    }

    /** The image: created once with a passphrase, mounted only with the same one. */
    static final class FakeVaultMount implements VaultMount {

        private final Path mountPoint;
        volatile State state = State.NO_IMAGE;
        volatile String passphrase;
        final AtomicInteger creates = new AtomicInteger();
        final AtomicInteger mounts = new AtomicInteger();
        final AtomicInteger unmounts = new AtomicInteger();
        volatile boolean forceNextUnmount;
        volatile boolean failUnmount;

        FakeVaultMount(Path mountPoint) {
            this.mountPoint = mountPoint;
        }

        void reset() {
            state = State.NO_IMAGE;
            passphrase = null;
            creates.set(0);
            mounts.set(0);
            unmounts.set(0);
            forceNextUnmount = false;
            failUnmount = false;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public void create(char[] key) {
            if (state != State.NO_IMAGE) {
                throw new MountException("an image exists");
            }
            try {
                Files.createDirectories(mountPoint.getParent()); // as create.sh's caller does
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            passphrase = new String(key);
            creates.incrementAndGet();
            state = State.UNMOUNTED;
        }

        @Override
        public void mount(char[] key) {
            if (state != State.UNMOUNTED) {
                throw new MountException("not mountable in state " + state);
            }
            if (!new String(key).equals(passphrase)) {
                throw new WrongKeyException();
            }
            try {
                Files.createDirectories(mountPoint.resolve("pgdata"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            mounts.incrementAndGet();
            state = State.MOUNTED;
        }

        @Override
        public Unmounted unmount() {
            if (failUnmount) {
                throw new MountException("unmount failed");
            }
            unmounts.incrementAndGet();
            state = state == State.NO_IMAGE ? State.NO_IMAGE : State.UNMOUNTED;
            if (forceNextUnmount) {
                forceNextUnmount = false;
                return Unmounted.FORCED;
            }
            return Unmounted.PLAIN;
        }

        @Override
        public void deleteNewImage() {
            state = State.NO_IMAGE;
            passphrase = null;
        }

        @Override
        public Path mountPoint() {
            return mountPoint;
        }
    }

    /** The cluster: a scratch database in the Testcontainers Postgres, created on first start. */
    static final class FakeDatabaseRuntime implements DatabaseRuntime {

        private final PostgreSQLContainer postgres;
        volatile ReadyCheck check = ReadyCheck.OK;
        volatile boolean running;
        volatile boolean failStart;
        final AtomicInteger inits = new AtomicInteger();
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        FakeDatabaseRuntime(PostgreSQLContainer postgres) {
            this.postgres = postgres;
        }

        void reset() {
            check = ReadyCheck.OK;
            running = false;
            failStart = false;
            inits.set(0);
            starts.set(0);
            stops.set(0);
        }

        @Override
        public ReadyCheck check() {
            return check;
        }

        @Override
        public void init(char[] password) {
            inits.incrementAndGet();
        }

        @Override
        public Connection start() {
            if (failStart) {
                throw new DatabaseException("start failed");
            }
            createScratchDatabase(postgres);
            starts.incrementAndGet();
            running = true;
            return new Connection(scratchUrl(postgres), postgres.getUsername(), postgres.getPassword());
        }

        @Override
        public Stopped stop() {
            stops.incrementAndGet();
            running = false;
            return Stopped.FAST;
        }
    }

    static String scratchUrl(PostgreSQLContainer postgres) {
        return postgres.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + SCRATCH_DATABASE + "$1");
    }

    static java.sql.Connection admin(PostgreSQLContainer postgres) throws SQLException {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    static synchronized void createScratchDatabase(PostgreSQLContainer postgres) {
        try (java.sql.Connection c = admin(postgres); Statement s = c.createStatement()) {
            var rows = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + SCRATCH_DATABASE + "'");
            if (!rows.next()) {
                s.execute("CREATE DATABASE " + SCRATCH_DATABASE);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Wall and monotonic time the test moves; {@link #sleep} moves only the wall clock, as a machine's sleep does. */
    static final class MutableVaultClock implements VaultClock {

        private volatile Instant wall = Instant.parse("2026-10-27T12:00:00Z");
        private volatile long monotonic = 1_000_000_000L;

        @Override
        public Instant wall() {
            return wall;
        }

        @Override
        public long monotonicNanos() {
            return monotonic;
        }

        synchronized void advance(Duration duration) {
            wall = wall.plus(duration);
            monotonic += duration.toNanos();
        }

        synchronized void sleep(Duration duration) {
            wall = wall.plus(duration);
        }
    }

    /** Counts the vault's events. */
    static final class VaultEvents {

        final List<Object> seen = new ArrayList<>();

        @EventListener
        synchronized void unlocked(VaultUnlocked event) {
            seen.add(event);
        }

        @EventListener
        synchronized void locking(VaultLocking event) {
            seen.add(event);
        }

        synchronized long count(Class<?> type) {
            return seen.stream().filter(type::isInstance).count();
        }

        synchronized void clear() {
            seen.clear();
        }
    }
}
