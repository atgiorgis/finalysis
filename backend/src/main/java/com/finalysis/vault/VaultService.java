package com.finalysis.vault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The vault's state machine (docs/plans/phase-8.md §4.5, §4.6, §5): setup, unlock, recovery, password change, lock,
 * the idle and wake locks, and the lock of a vault left mounted at startup. One transition at a time. With the vault
 * off ({@code finalysis.vault.enabled=false}) it opens the plain database at startup and stays unlocked; lock is then
 * a no-op.
 *
 * <p>Logs name the step and an exception's class only: never a password, a key, a path or a tool's output.
 */
public class VaultService {

    private static final Logger log = LoggerFactory.getLogger(VaultService.class);
    static final Duration LOCK_WAIT = Duration.ofSeconds(30);
    static final Duration WAKE_GAP = Duration.ofMinutes(1);
    static final String REFRESH_ACTION = "Postgres.app's ICU library changed. With the vault unlocked, run "
            + "scripts/vault/db-refresh-collation.sh (it rebuilds the text indexes with REINDEX DATABASE, then runs "
            + "ALTER DATABASE finalysis REFRESH COLLATION VERSION).";

    private final VaultProperties properties;
    private final VaultMount mount;
    private final DatabaseRuntime runtime;
    private final VaultDatabase database;
    private final KdfPolicy kdf;
    private final PasswordPolicy passwords;
    private final UnlockAttempts attempts;
    private final VaultRequests requests;
    private final VaultClock clock;
    private final ApplicationEventPublisher events;
    private final PlainDatabaseConnection plain;
    private final SecureRandom random = new SecureRandom();
    private final ReentrantLock transition = new ReentrantLock();

    private volatile VaultState state = VaultState.LOCKED;
    private volatile boolean setUp;
    private volatile String reason;
    private volatile String message;
    private volatile boolean lastLockForced;
    private volatile VaultDatabase.CollationChange collation;
    private volatile int idleLockMinutes;
    private volatile long lastActivityNanos;
    private long lastWallMillis;
    private long lastMonotonicNanos;

    VaultService(VaultProperties properties, VaultMount mount, DatabaseRuntime runtime, VaultDatabase database,
                 KdfPolicy kdf, PasswordPolicy passwords, UnlockAttempts attempts, VaultRequests requests,
                 VaultClock clock, ApplicationEventPublisher events, PlainDatabaseConnection plain) {
        this.properties = properties;
        this.mount = mount;
        this.runtime = runtime;
        this.database = database;
        this.kdf = kdf;
        this.passwords = passwords;
        this.attempts = attempts;
        this.requests = requests;
        this.clock = clock;
        this.events = events;
        this.plain = plain;
        this.idleLockMinutes = properties.idleLockMinutes();
    }

    // ---- startup and shutdown -------------------------------------------------------------------------------------

    /** At startup: with the vault off, opens the plain database; on, locks a vault left mounted and reads the state. */
    void start() {
        transition.lock();
        try {
            if (!properties.enabled()) {
                database.open(plain.url(), plain.user(), plain.password());
                afterOpen();
                return;
            }
            createVaultDirectory();
            lockLeftMounted();
            evaluate();
        } finally {
            transition.unlock();
        }
    }

    /** At shutdown (SIGTERM, Ctrl-C, the build stopping the app): locks an open vault. */
    void shutdown() {
        lock("shutdown");
    }

    /** The vault directory exists (mode 0700) from the first start, so the startup check can look for conflicts. */
    private void createVaultDirectory() {
        try {
            if (!Files.isDirectory(properties.vaultDir())) {
                Files.createDirectories(properties.vaultDir(), java.nio.file.attribute.PosixFilePermissions
                        .asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
            }
        } catch (IOException e) {
            log.error("The vault directory could not be created ({})", e.getClass().getName());
        }
    }

    private void lockLeftMounted() {
        VaultMount.State image;
        try {
            image = mount.state();
        } catch (RuntimeException e) {
            return; // evaluate() reports it
        }
        if (image != VaultMount.State.MOUNTED) {
            return;
        }
        log.warn("The vault was still mounted at startup (the backend was stopped without locking); locking it");
        try {
            runtime.stop();
        } catch (RuntimeException e) {
            log.error("Stopping the vault's database at startup failed ({})", e.getClass().getName());
        }
        try {
            lastLockForced = mount.unmount() == VaultMount.Unmounted.FORCED;
        } catch (RuntimeException e) {
            log.error("Unmounting the vault at startup failed ({})", e.getClass().getName());
        }
    }

    /** Sets the state from what is on disk, then the startup check. */
    private void evaluate() {
        reason = null;
        message = null;
        boolean keyFile = Files.exists(properties.keyFile());
        VaultMount.State image;
        try {
            image = mount.state();
        } catch (RuntimeException e) {
            broken("image-unreadable", "The vault's disk image could not be checked. See the README's vault section.");
            return;
        }
        if (image == VaultMount.State.MOUNTED) {
            broken("still-mounted", "The vault's disk image is still open and could not be closed. Restart the "
                    + "computer, then Finalysis.");
            return;
        }
        if (image == VaultMount.State.ELSEWHERE) {
            broken("image-attached-elsewhere", "The vault's disk image is open somewhere else. Eject it, then restart "
                    + "Finalysis.");
            return;
        }
        boolean hasImage = image == VaultMount.State.UNMOUNTED;
        if (keyFile != hasImage) {
            broken(keyFile ? "image-missing" : "key-file-missing", keyFile
                    ? "The vault's key file is there but its disk image is missing. Restore the image from a backup; "
                            + "Finalysis never makes a new vault over an old one."
                    : "The vault's disk image is there but its key file is missing. Restore vault-key.json from a "
                            + "backup; Finalysis never makes a new vault over an old one.");
            return;
        }
        setUp = keyFile;
        state = setUp ? VaultState.LOCKED : VaultState.NOT_SET_UP;
        ready();
    }

    private void broken(String code, String text) {
        state = VaultState.BROKEN;
        reason = code;
        message = text;
    }

    /** Runs the startup check; a failure sets NOT_READY with its reason, a pass clears it. */
    private ReadyCheck ready() {
        ReadyCheck check = runtime.check();
        if (check.ok()) {
            if (state == VaultState.NOT_READY) {
                state = setUp ? VaultState.LOCKED : VaultState.NOT_SET_UP;
            }
            reason = null;
            message = null;
        } else {
            state = VaultState.NOT_READY;
            reason = check.reason();
            message = check.message();
        }
        return check;
    }

    private void requireReady() {
        ReadyCheck check = ready();
        if (!check.ok()) {
            throw VaultException.notReady(check);
        }
    }

    // ---- setup, unlock, recovery, password change -----------------------------------------------------------------

    /** Creates the vault; returns the recovery key, shown once (§4.5). */
    String setup(String password) {
        transition.lock();
        try {
            if (!properties.enabled() || setUp
                    || (state != VaultState.NOT_SET_UP && state != VaultState.NOT_READY)) {
                throw VaultException.state(state, "setup is only for a new vault.");
            }
            passwords.check(password);
            requireReady();
            state = VaultState.UNLOCKING;
            boolean imageCreated = false;
            boolean mounted = false;
            boolean keyWritten = false;
            char[] chars = password.toCharArray();
            try (RecoveryKey key = RecoveryKey.generate(random)) {
                VaultKeyFile.Contents contents = VaultKeyFile.wrap(key.bytes(), chars, kdf.calibrate(), random);
                char[] passphrase = key.passphrase();
                try {
                    mount.create(passphrase);
                    imageCreated = true;
                    mount.mount(passphrase);
                    mounted = true;
                } finally {
                    Arrays.fill(passphrase, '\0');
                }
                char[] databasePassword = newDatabasePassword();
                try {
                    runtime.init(databasePassword);
                } finally {
                    Arrays.fill(databasePassword, '\0');
                }
                VaultKeyFile.write(properties.keyFile(), contents);
                keyWritten = true;
                openMounted();
                setUp = true;
                log.info("Vault set up and unlocked");
                return key.formatted();
            } catch (RuntimeException e) {
                log.error("Vault setup failed ({}); removing what it made", e.getClass().getName());
                undoSetup(imageCreated, mounted, keyWritten);
                throw VaultException.setupFailed();
            } finally {
                Arrays.fill(chars, '\0');
            }
        } finally {
            transition.unlock();
        }
    }

    private void undoSetup(boolean imageCreated, boolean mounted, boolean keyWritten) {
        database.close();
        if (mounted) {
            try {
                runtime.stop();
            } catch (RuntimeException e) {
                log.error("Stopping the new vault's database failed ({})", e.getClass().getName());
            }
            try {
                mount.unmount();
            } catch (RuntimeException e) {
                log.error("Unmounting the new vault failed ({})", e.getClass().getName());
            }
        }
        if (imageCreated) {
            try {
                mount.deleteNewImage();
            } catch (RuntimeException e) {
                log.error("Deleting the new vault's image failed ({})", e.getClass().getName());
            }
        }
        if (keyWritten) {
            try {
                Files.deleteIfExists(properties.keyFile());
            } catch (IOException e) {
                log.error("Deleting the new vault's key file failed ({})", e.getClass().getName());
            }
        }
        setUp = false;
        state = VaultState.NOT_SET_UP;
    }

    /** Unlocks with the password (§4.2, §5.2). */
    void unlock(String password) {
        transition.lock();
        try {
            requireLocked("unlock is only for a locked vault.");
            attempts.check();
            requireReady();
            VaultKeyFile.Contents contents = readKeyFile();
            byte[] raw;
            char[] chars = password == null ? new char[0] : password.toCharArray();
            try {
                raw = VaultKeyFile.unwrap(contents, chars);
            } catch (VaultKeyFile.WrongPasswordException e) {
                attempts.failed();
                throw VaultException.wrongPassword();
            } catch (VaultKeyFile.DamagedException e) {
                throw VaultException.fileDamaged();
            } finally {
                Arrays.fill(chars, '\0');
            }
            attempts.succeeded();
            try (RecoveryKey key = RecoveryKey.of(raw)) {
                mountWith(key, false);
                openMounted();
                log.info("Vault unlocked");
            }
        } finally {
            transition.unlock();
        }
    }

    /** Unlocks with the recovery key and sets a new password (§4.2): for a forgotten password or a damaged key file. */
    void recover(String recoveryKey, String newPassword) {
        transition.lock();
        try {
            requireLocked("recovery is only for a locked vault.");
            attempts.check();
            passwords.check(newPassword);
            requireReady();
            Optional<RecoveryKey> parsed = RecoveryKey.parse(recoveryKey);
            if (parsed.isEmpty()) {
                attempts.failed();
                throw VaultException.wrongRecoveryKey();
            }
            try (RecoveryKey key = parsed.get()) {
                mountWith(key, true);
                attempts.succeeded();
                char[] chars = newPassword.toCharArray();
                try {
                    VaultKeyFile.write(properties.keyFile(),
                            VaultKeyFile.wrap(key.bytes(), chars, kdf.calibrate(), random));
                } catch (RuntimeException e) {
                    log.error("Writing the new key file after recovery failed ({})", e.getClass().getName());
                    closeMounted();
                    throw VaultException.databaseFailed();
                } finally {
                    Arrays.fill(chars, '\0');
                }
                openMounted();
                log.info("Vault recovered with the recovery key; new password set");
            }
        } finally {
            transition.unlock();
        }
    }

    /** Changes the password: unwrap with the old one, wrap with the new one; the image isn't touched (§4.2). */
    void changePassword(String currentPassword, String newPassword) {
        transition.lock();
        try {
            if (!properties.enabled() || state != VaultState.UNLOCKED) {
                throw VaultException.state(state, "the password can only be changed while unlocked.");
            }
            attempts.check();
            passwords.check(newPassword);
            passwords.checkPersonal(newPassword, database.accountTerms());
            VaultKeyFile.Contents contents = readKeyFile();
            char[] current = currentPassword == null ? new char[0] : currentPassword.toCharArray();
            char[] next = newPassword.toCharArray();
            byte[] raw = null;
            try {
                raw = VaultKeyFile.unwrap(contents, current);
                attempts.succeeded();
                VaultKeyFile.write(properties.keyFile(), VaultKeyFile.wrap(raw, next, kdf.calibrate(), random));
                log.info("Vault password changed");
            } catch (VaultKeyFile.WrongPasswordException e) {
                attempts.failed();
                throw VaultException.wrongPassword();
            } catch (VaultKeyFile.DamagedException e) {
                throw VaultException.fileDamaged();
            } finally {
                Arrays.fill(current, '\0');
                Arrays.fill(next, '\0');
                if (raw != null) {
                    Arrays.fill(raw, (byte) 0);
                }
            }
        } finally {
            transition.unlock();
        }
    }

    private void requireLocked(String what) {
        if (!properties.enabled()) {
            throw VaultException.state(state, what);
        }
        if (state == VaultState.NOT_READY && setUp) {
            state = VaultState.LOCKED;
        }
        if (state != VaultState.LOCKED) {
            throw VaultException.state(state, what);
        }
    }

    private VaultKeyFile.Contents readKeyFile() {
        try {
            return VaultKeyFile.read(properties.keyFile(), kdf.floor());
        } catch (VaultKeyFile.DamagedException e) {
            throw VaultException.fileDamaged();
        }
    }

    /** Mounts the image with the key; a wrong key is a wrong recovery key or (from the key file) a mount failure. */
    private void mountWith(RecoveryKey key, boolean typedByUser) {
        state = VaultState.UNLOCKING;
        char[] passphrase = key.passphrase();
        try {
            mount.mount(passphrase);
        } catch (VaultMount.WrongKeyException e) {
            state = VaultState.LOCKED;
            if (typedByUser) {
                attempts.failed();
                throw VaultException.wrongRecoveryKey();
            }
            log.error("The key from the key file did not open the image");
            throw VaultException.mountFailed();
        } catch (RuntimeException e) {
            state = VaultState.LOCKED;
            log.error("Mounting the vault failed ({})", e.getClass().getName());
            throw VaultException.mountFailed();
        } finally {
            Arrays.fill(passphrase, '\0');
        }
    }

    /** Starts the database on the mounted image and opens it; on failure, closes everything and stays locked. */
    private void openMounted() {
        try {
            DatabaseRuntime.Connection connection = runtime.start();
            database.open(connection.url(), connection.user(), connection.password());
        } catch (RuntimeException e) {
            log.error("Opening the vault's database failed ({}); locking again", e.getClass().getName());
            closeMounted();
            throw VaultException.databaseFailed();
        }
        afterOpen();
    }

    private void closeMounted() {
        database.close();
        try {
            runtime.stop();
        } catch (RuntimeException e) {
            log.error("Stopping the vault's database failed ({})", e.getClass().getName());
        }
        try {
            mount.unmount();
        } catch (RuntimeException e) {
            log.error("Unmounting the vault failed ({})", e.getClass().getName());
        }
        state = VaultState.LOCKED;
    }

    private void afterOpen() {
        collation = database.collationChange();
        if (collation != null) {
            log.warn("The vault database's collation version changed (recorded {}, now {}): run "
                    + "scripts/vault/db-refresh-collation.sh while unlocked", collation.recorded(), collation.actual());
        }
        idleLockMinutes = database.idleLockMinutes(properties.idleLockMinutes());
        touch();
        resetWake();
        reason = null;
        message = null;
        state = VaultState.UNLOCKED;
        try {
            events.publishEvent(new VaultUnlocked());
        } catch (RuntimeException e) {
            log.error("A listener of the vault's unlock failed ({}); the vault stays unlocked",
                    e.getClass().getName());
        }
    }

    private char[] newDatabasePassword() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        byte[] encoded = Base64.getEncoder().encode(bytes);
        char[] chars = new char[encoded.length];
        for (int i = 0; i < encoded.length; i++) {
            chars[i] = (char) encoded[i];
        }
        Arrays.fill(bytes, (byte) 0);
        Arrays.fill(encoded, (byte) 0);
        return chars;
    }

    // ---- lock -----------------------------------------------------------------------------------------------------

    /**
     * Locks (§4.6): stop admitting requests, let background work stop, wait for requests in flight (30 s), close the
     * pool, stop the database, unmount. Each part is tried even if an earlier one failed; a failure leaves LOCK_FAILED
     * naming the parts. A no-op with the vault off, or when nothing is open.
     */
    void lock(String why) {
        if (!properties.enabled()) {
            return;
        }
        transition.lock();
        try {
            if (state != VaultState.UNLOCKED && state != VaultState.LOCK_FAILED) {
                return;
            }
            state = VaultState.LOCKING;
            log.info("Vault locking ({})", why);
            List<String> failed = new ArrayList<>();
            try {
                events.publishEvent(new VaultLocking());
            } catch (RuntimeException e) {
                log.error("A listener of the vault's lock failed ({})", e.getClass().getName());
            }
            if (!requests.awaitIdle(LOCK_WAIT)) {
                log.warn("Vault lock: requests still running after {} s; closing the database under them",
                        LOCK_WAIT.toSeconds());
            }
            try {
                database.close();
            } catch (RuntimeException e) {
                failed.add("pool");
            }
            try {
                if (runtime.stop() == DatabaseRuntime.Stopped.IMMEDIATE) {
                    log.warn("Vault lock: the database needed an immediate stop");
                }
            } catch (RuntimeException e) {
                failed.add("database");
            }
            try {
                lastLockForced = mount.unmount() == VaultMount.Unmounted.FORCED;
                if (lastLockForced) {
                    log.warn("Vault lock: the unmount had to be forced");
                }
            } catch (RuntimeException e) {
                failed.add("unmount");
            }
            // Sessions and the LAN listener close here from step 5 on (§9).
            collation = null;
            if (failed.isEmpty()) {
                state = VaultState.LOCKED;
                reason = null;
                message = null;
                log.info("Vault locked");
            } else {
                state = VaultState.LOCK_FAILED;
                reason = String.join(",", failed);
                message = "Locking did not finish (" + reason + "). Try Lock again; if it keeps failing, restart the "
                        + "computer.";
                log.error("Vault lock failed: {}", reason);
            }
        } finally {
            transition.unlock();
        }
    }

    // ---- idle, wake, activity -------------------------------------------------------------------------------------

    /** Any gated request, and {@code POST /api/vault/activity}: the user is here. */
    void touch() {
        lastActivityNanos = clock.monotonicNanos();
    }

    /** Every 10 seconds: lock after a sleep, or after the idle minutes. */
    void tick() {
        if (!properties.enabled() || state != VaultState.UNLOCKED) {
            return;
        }
        if (slept()) {
            lock("wake");
            return;
        }
        long idle = clock.monotonicNanos() - lastActivityNanos;
        if (idle >= Duration.ofMinutes(idleLockMinutes).toNanos()) {
            lock("idle");
        }
    }

    /** Before each gated request: true when it must be refused because the machine slept (and the vault locked). */
    boolean lockIfSlept() {
        if (!properties.enabled() || state != VaultState.UNLOCKED) {
            return false;
        }
        if (slept()) {
            lock("wake");
            return true;
        }
        return false;
    }

    /** True when the wall clock ran ahead of the monotonic clock by more than a minute since the last look. */
    private synchronized boolean slept() {
        long wall = clock.wall().toEpochMilli();
        long monotonic = clock.monotonicNanos();
        long wallGap = wall - lastWallMillis;
        long monotonicGap = (monotonic - lastMonotonicNanos) / 1_000_000;
        lastWallMillis = wall;
        lastMonotonicNanos = monotonic;
        return wallGap - monotonicGap > WAKE_GAP.toMillis();
    }

    private synchronized void resetWake() {
        lastWallMillis = clock.wall().toEpochMilli();
        lastMonotonicNanos = clock.monotonicNanos();
    }

    // ---- status ---------------------------------------------------------------------------------------------------

    public VaultState state() {
        return state;
    }

    public VaultStatus status() {
        VaultDatabase.CollationChange change = collation;
        return new VaultStatus(state, properties.enabled() ? "VAULT" : "PLAIN", reason, message, idleLockMinutes,
                false, lastLockForced,
                change == null ? null : new VaultStatus.Collation(change.recorded(), change.actual(), REFRESH_ACTION));
    }

    boolean enabled() {
        return properties.enabled();
    }

    /** Tests share one context: forget the attempts and what the last lock left, before {@link #start()} again. */
    void resetForTests() {
        attempts.succeeded();
        lastLockForced = false;
        collation = null;
        setUp = false;
    }

    Path mountPoint() {
        return mount.mountPoint();
    }
}
