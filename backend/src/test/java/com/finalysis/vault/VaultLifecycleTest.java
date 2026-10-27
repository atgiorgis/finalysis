package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finalysis.vault.VaultFakes.FakeDatabaseRuntime;
import com.finalysis.vault.VaultFakes.FakeVaultMount;
import com.finalysis.vault.VaultFakes.MutableVaultClock;
import com.finalysis.vault.VaultFakes.VaultEvents;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The vault's lifecycle through its API (docs/plans/phase-8.md §4.5, §4.6, §5, §10 "Lifecycle", "Recovery, password
 * change", "Idle and sleep"): setup with the recovery key, lock, unlock, the rate limit, recovery, password change,
 * the idle and wake locks, a vault left mounted at startup, shutdown, NOT_READY, a damaged key file, a database that
 * won't open, and the collation notice. Fakes for the image and the runtime; a scratch database in Testcontainers.
 */
@VaultEnabledTest
class VaultLifecycleTest {

    private static final String PASSWORD = "lifecycle password, synthetic";
    private static final String NEW_PASSWORD = "a different synthetic password";
    private static final String KEY_PATTERN = "([0-9A-HJKMNP-TV-Z]{4}-){12}[0-9A-HJKMNP-TV-Z]{4}";

    private final MockMvc mvc;
    private final JsonMapper json;
    private final VaultService vault;
    private final VaultProperties properties;
    private final VaultRequests requests;
    private final FakeVaultMount mount;
    private final FakeDatabaseRuntime runtime;
    private final MutableVaultClock clock;
    private final VaultEvents events;
    private final PostgreSQLContainer postgres;

    VaultLifecycleTest(MockMvc mvc, JsonMapper json, VaultService vault, VaultProperties properties,
                       VaultRequests requests, FakeVaultMount mount, FakeDatabaseRuntime runtime,
                       MutableVaultClock clock, VaultEvents events, PostgreSQLContainer postgres) {
        this.mvc = mvc;
        this.json = json;
        this.vault = vault;
        this.properties = properties;
        this.requests = requests;
        this.mount = mount;
        this.runtime = runtime;
        this.clock = clock;
        this.events = events;
        this.postgres = postgres;
    }

    @BeforeEach
    void notSetUp() {
        VaultTestState.reset(vault, properties, mount, runtime, events);
        assertThat(vault.state()).isEqualTo(VaultState.NOT_SET_UP);
    }

    // ---- setup ----------------------------------------------------------------------------------------------------

    @Test
    void setupReturnsTheRecoveryKeyOnceAndUnlocks() throws Exception {
        MockHttpServletResponse response = postJson("/api/vault/setup", "{\"password\":\"" + PASSWORD + "\"}");

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        String key = json.readTree(response.getContentAsString()).get("recoveryKey").asString();
        assertThat(key).matches(KEY_PATTERN);
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);
        assertThat(mount.creates.get()).isEqualTo(1);
        assertThat(mount.passphrase).isEqualTo(key.replace("-", ""));
        assertThat(runtime.inits.get()).isEqualTo(1);
        assertThat(Files.getPosixFilePermissions(properties.keyFile()))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readString(properties.keyFile())).doesNotContain(PASSWORD).doesNotContain(key);
        assertThat(events.count(VaultUnlocked.class)).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE success")).isNotEqualTo("0");
        assertThat(get200("/api/accounts")).isEqualTo(200);

        assertThat(postJson("/api/vault/setup", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(409);
    }

    @Test
    void setupRefusesAShortPasswordAndChangesNothing() throws Exception {
        MockHttpServletResponse response = postJson("/api/vault/setup", "{\"password\":\"too short\"}");

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(problemType(response)).isEqualTo("urn:finalysis:problem:password-rejected");
        assertThat(response.getContentAsString()).doesNotContain("too short");
        assertThat(vault.state()).isEqualTo(VaultState.NOT_SET_UP);
        assertThat(mount.creates.get()).isZero();
        assertThat(properties.keyFile()).doesNotExist();
    }

    @Test
    void aSetupThatFailsPartwayRemovesWhatItMade() throws Exception {
        runtime.failStart = true;

        MockHttpServletResponse response = postJson("/api/vault/setup", "{\"password\":\"" + PASSWORD + "\"}");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(problemType(response)).isEqualTo("urn:finalysis:problem:setup-failed");
        assertThat(vault.state()).isEqualTo(VaultState.NOT_SET_UP);
        assertThat(mount.state()).isEqualTo(VaultMount.State.NO_IMAGE);
        assertThat(properties.keyFile()).doesNotExist();
    }

    // ---- lock and unlock ------------------------------------------------------------------------------------------

    @Test
    void lockClosesEveryConnectionStopsTheDatabaseAndUnmounts() throws Exception {
        vault.setup(PASSWORD);
        assertThat(get200("/api/accounts")).isEqualTo(200);
        assertThat(poolConnections()).isPositive();

        assertThat(mvc.perform(post("/api/vault/lock")).andReturn().getResponse().getStatus()).isEqualTo(204);

        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        assertThat(poolConnections()).isZero();
        assertThat(runtime.running).isFalse();
        assertThat(mount.state()).isEqualTo(VaultMount.State.UNMOUNTED);
        assertThat(events.count(VaultLocking.class)).isEqualTo(1);
        assertThat(get200("/api/accounts")).isEqualTo(423);
        assertThat(status().get("lastLockForced").asBoolean()).isFalse();
    }

    @Test
    void aForcedUnmountIsReportedOnTheStatus() throws Exception {
        vault.setup(PASSWORD);
        mount.forceNextUnmount = true;

        vault.lock("test");

        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        assertThat(status().get("lastLockForced").asBoolean()).isTrue();
    }

    @Test
    void aFailedPartOfTheLockLeavesLockFailedAndEverythingStillRefused() throws Exception {
        vault.setup(PASSWORD);
        mount.failUnmount = true;

        vault.lock("test");

        assertThat(vault.state()).isEqualTo(VaultState.LOCK_FAILED);
        assertThat(status().get("reason").asString()).isEqualTo("unmount");
        assertThat(poolConnections()).isZero();
        assertThat(get200("/api/accounts")).isEqualTo(423);

        mount.failUnmount = false;
        vault.lock("retry");
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    @Test
    void unlockWithTheRightPasswordOnlyAndEachUnlockPublishesTheEvent() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");

        MockHttpServletResponse wrong = postJson("/api/vault/unlock", "{\"password\":\"not the password at all\"}");
        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(problemType(wrong)).isEqualTo("urn:finalysis:problem:wrong-password");
        assertThat(wrong.getContentAsString()).doesNotContain("not the password");
        assertThat(mount.state()).isEqualTo(VaultMount.State.UNMOUNTED);

        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(204);
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);
        assertThat(get200("/api/accounts")).isEqualTo(200);
        assertThat(events.count(VaultUnlocked.class)).isEqualTo(2);
    }

    @Test
    void afterFiveWrongPasswordsEachAttemptWaitsEvenTheRightOne() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");
        for (int i = 0; i < UnlockAttempts.FREE_ATTEMPTS; i++) {
            assertThat(postJson("/api/vault/unlock", "{\"password\":\"wrong password " + i + "\"}").getStatus())
                    .isEqualTo(401);
        }

        MockHttpServletResponse blocked = postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getHeader("Retry-After")).isEqualTo("30");
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);

        clock.advance(Duration.ofSeconds(31));
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(204);
    }

    @Test
    void lockWaitsForARequestInFlight() throws Exception {
        vault.setup(PASSWORD);
        requests.enter();
        CompletableFuture<Void> locking = CompletableFuture.runAsync(() -> vault.lock("test"));
        Thread.sleep(300);
        assertThat(vault.state()).isEqualTo(VaultState.LOCKING);
        assertThat(get200("/api/accounts")).isEqualTo(423);

        requests.exit();

        locking.get(10, TimeUnit.SECONDS);
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    @Test
    void aDatabaseThatWontOpenLeavesTheVaultLockedAndUnmounted() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");
        runtime.failStart = true;

        MockHttpServletResponse response = postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}");

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(problemType(response)).isEqualTo("urn:finalysis:problem:database-failed");
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        assertThat(mount.state()).isEqualTo(VaultMount.State.UNMOUNTED);
    }

    // ---- recovery and password change -----------------------------------------------------------------------------

    @Test
    void theRecoveryKeySetsANewPasswordAndTheOldOneStopsWorking() throws Exception {
        String key = vault.setup(PASSWORD);
        vault.lock("test");
        String typed = key.toLowerCase().replace("-", " ").replace('1', 'l').replace('0', 'o');

        MockHttpServletResponse recovered = postJson("/api/vault/recover",
                "{\"recoveryKey\":\"" + typed + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}");

        assertThat(recovered.getStatus()).isEqualTo(204);
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);
        vault.lock("test");
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(401);
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + NEW_PASSWORD + "\"}").getStatus())
                .isEqualTo(204);
        assertThat(mount.creates.get()).isEqualTo(1);
    }

    @Test
    void aWrongOrMalformedRecoveryKeyIsRefused() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");
        String otherKey;
        try (RecoveryKey other = RecoveryKey.generate(new java.security.SecureRandom())) {
            otherKey = other.formatted();
        }

        MockHttpServletResponse wrong = postJson("/api/vault/recover",
                "{\"recoveryKey\":\"" + otherKey + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}");
        MockHttpServletResponse malformed = postJson("/api/vault/recover",
                "{\"recoveryKey\":\"ABCD-EFGH\",\"newPassword\":\"" + NEW_PASSWORD + "\"}");

        assertThat(wrong.getStatus()).isEqualTo(401);
        assertThat(problemType(wrong)).isEqualTo("urn:finalysis:problem:wrong-recovery-key");
        assertThat(wrong.getContentAsString()).doesNotContain(otherKey);
        assertThat(malformed.getStatus()).isEqualTo(401);
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(204);
    }

    @Test
    void aDamagedKeyFileIsReportedAndTheRecoveryKeyStillOpensTheVault() throws Exception {
        String key = vault.setup(PASSWORD);
        vault.lock("test");
        Files.writeString(properties.keyFile(), "{ not a key file");

        MockHttpServletResponse damaged = postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}");
        assertThat(damaged.getStatus()).isEqualTo(500);
        assertThat(problemType(damaged)).isEqualTo("urn:finalysis:problem:vault-file-damaged");
        assertThat(damaged.getContentAsString()).contains("recovery key");

        assertThat(postJson("/api/vault/recover",
                "{\"recoveryKey\":\"" + key + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}").getStatus())
                .isEqualTo(204);
        vault.lock("test");
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + NEW_PASSWORD + "\"}").getStatus())
                .isEqualTo(204);
    }

    @Test
    void passwordChangeNeedsTheCurrentPasswordAndLeavesTheImageAlone() throws Exception {
        vault.setup(PASSWORD);
        String passphrase = mount.passphrase;
        String before = Files.readString(properties.keyFile());

        assertThat(postJson("/api/vault/password", "{\"currentPassword\":\"wrong current password\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}").getStatus()).isEqualTo(401);
        assertThat(postJson("/api/vault/password", "{\"currentPassword\":\"" + PASSWORD
                + "\",\"newPassword\":\"short\"}").getStatus()).isEqualTo(422);
        assertThat(postJson("/api/vault/password", "{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\""
                + NEW_PASSWORD + "\"}").getStatus()).isEqualTo(204);

        assertThat(Files.readString(properties.keyFile())).isNotEqualTo(before);
        assertThat(mount.passphrase).isEqualTo(passphrase);
        assertThat(mount.creates.get()).isEqualTo(1);
        vault.lock("test");
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(401);
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + NEW_PASSWORD + "\"}").getStatus())
                .isEqualTo(204);
    }

    @Test
    void passwordChangeRefusesAPasswordContainingAnAccountNameOrInstitution() throws Exception {
        vault.setup(PASSWORD);
        execute("INSERT INTO account (name, institution, type, last_four) "
                + "VALUES ('Household Checking', 'Synthetic Credit Union', 'CHECKING', '9911')");
        try {
            MockHttpServletResponse byName = postJson("/api/vault/password", "{\"currentPassword\":\"" + PASSWORD
                    + "\",\"newPassword\":\"our household-checking pw\"}");
            MockHttpServletResponse byInstitution = postJson("/api/vault/password", "{\"currentPassword\":\""
                    + PASSWORD + "\",\"newPassword\":\"SyntheticCreditUnion#1\"}");

            for (MockHttpServletResponse response : new MockHttpServletResponse[] {byName, byInstitution}) {
                assertThat(response.getStatus()).isEqualTo(422);
                assertThat(problemType(response)).isEqualTo("urn:finalysis:problem:password-rejected");
                assertThat(response.getContentAsString()).doesNotContainIgnoringCase("household")
                        .doesNotContainIgnoringCase("synthetic credit");
            }
            assertThat(postJson("/api/vault/password", "{\"currentPassword\":\"" + PASSWORD
                    + "\",\"newPassword\":\"" + NEW_PASSWORD + "\"}").getStatus()).isEqualTo(204);
        } finally {
            execute("DELETE FROM account WHERE last_four = '9911' AND institution = 'Synthetic Credit Union'");
        }
    }

    // ---- idle, wake, startup, shutdown ----------------------------------------------------------------------------

    @Test
    void idleLockAfterTheMinutesActivityResetsItAndStatusDoesNot() throws Exception {
        vault.setup(PASSWORD);

        clock.advance(Duration.ofMinutes(14));
        assertThat(mvc.perform(post("/api/vault/activity")).andReturn().getResponse().getStatus()).isEqualTo(204);
        clock.advance(Duration.ofMinutes(14));
        vault.tick();
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);

        assertThat(get200("/api/status")).isEqualTo(200);
        clock.advance(Duration.ofMinutes(2));
        vault.tick();
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    @Test
    void aGatedRequestCountsAsActivity() throws Exception {
        vault.setup(PASSWORD);
        clock.advance(Duration.ofMinutes(14));
        assertThat(get200("/api/accounts")).isEqualTo(200);
        clock.advance(Duration.ofMinutes(14));
        vault.tick();
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);
    }

    @Test
    void theIdleMinutesComeFromTheAppSetting() throws Exception {
        vault.setup(PASSWORD);
        execute("INSERT INTO app_setting (key, value) VALUES ('vault.idle-lock-minutes', '5') "
                + "ON CONFLICT (key) DO UPDATE SET value = '5'");
        vault.lock("test");
        vault.unlock(PASSWORD);
        try {
            assertThat(status().get("idleLockMinutes").asInt()).isEqualTo(5);
            clock.advance(Duration.ofMinutes(6));
            vault.tick();
            assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        } finally {
            if (vault.state() != VaultState.UNLOCKED) {
                vault.unlock(PASSWORD);
            }
            execute("DELETE FROM app_setting WHERE key = 'vault.idle-lock-minutes'");
        }
    }

    @Test
    void afterASleepTheNextRequestLocksFirstAndIsRefused() throws Exception {
        vault.setup(PASSWORD);
        clock.sleep(Duration.ofMinutes(5));

        assertThat(get200("/api/accounts")).isEqualTo(423);
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    @Test
    void theTickerLocksAfterASleepToo() {
        vault.setup(PASSWORD);
        clock.advance(Duration.ofSeconds(10));
        vault.tick();
        assertThat(vault.state()).isEqualTo(VaultState.UNLOCKED);

        clock.sleep(Duration.ofMinutes(2));
        vault.tick();
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    @Test
    void aVaultLeftMountedIsLockedAtStartup() {
        vault.setup(PASSWORD);
        int stops = runtime.stops.get();
        // The backend was killed: the image is still mounted when the next one starts.
        vault.start();

        assertThat(runtime.stops.get()).isEqualTo(stops + 1);
        assertThat(mount.state()).isEqualTo(VaultMount.State.UNMOUNTED);
        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
    }

    /** Found by the first run on a real vault: the startup check needs the directory, so a first start makes it. */
    @Test
    void theFirstStartCreatesTheVaultDirectoryOwnerOnly() throws Exception {
        VaultTestSupport.deleteRecursively(properties.vaultDir());

        vault.start();

        assertThat(Files.getPosixFilePermissions(properties.vaultDir())).containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
        assertThat(vault.state()).isEqualTo(VaultState.NOT_SET_UP);
    }

    @Test
    void shutdownLocks() {
        vault.setup(PASSWORD);

        vault.shutdown();

        assertThat(vault.state()).isEqualTo(VaultState.LOCKED);
        assertThat(mount.state()).isEqualTo(VaultMount.State.UNMOUNTED);
    }

    @Test
    void aKeyFileWithoutItsImageIsBrokenNeverRepaired() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");
        mount.reset(); // the image is gone

        vault.start();

        assertThat(vault.state()).isEqualTo(VaultState.BROKEN);
        assertThat(status().get("reason").asString()).isEqualTo("image-missing");
        assertThat(postJson("/api/vault/setup", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(409);
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(409);
    }

    // ---- not ready, collation, status -----------------------------------------------------------------------------

    @Test
    void notReadyNamesTheVersionsAndClearsOnceFixed() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");
        runtime.check = ReadyCheck.parse("NOT_READY pgvector-wrong-version installed=0.8.7 required=0.8.6");

        MockHttpServletResponse response = postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}");

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(problemType(response)).isEqualTo("urn:finalysis:problem:vault-not-ready");
        JsonNode status = status();
        assertThat(status.get("state").asString()).isEqualTo("NOT_READY");
        assertThat(status.get("reason").asString()).isEqualTo("pgvector-wrong-version");
        assertThat(status.get("message").asString()).contains("0.8.6", "0.8.7");

        runtime.check = ReadyCheck.OK;
        assertThat(postJson("/api/vault/unlock", "{\"password\":\"" + PASSWORD + "\"}").getStatus()).isEqualTo(204);
    }

    @Test
    void aChangedCollationVersionIsReportedWithTheStepToTake() throws Exception {
        vault.setup(PASSWORD);
        vault.lock("test");
        try (Connection c = VaultFakes.admin(postgres); Statement s = c.createStatement()) {
            s.execute("UPDATE pg_database SET datcollversion = '0.0' WHERE datname = '"
                    + VaultFakes.SCRATCH_DATABASE + "'");
        }
        try {
            vault.unlock(PASSWORD);

            JsonNode collation = status().get("collation");
            assertThat(collation.get("recorded").asString()).isEqualTo("0.0");
            assertThat(collation.get("actual").asString()).isNotEqualTo("0.0");
            assertThat(collation.get("action").asString())
                    .contains("db-refresh-collation.sh", "REINDEX DATABASE", "REFRESH COLLATION VERSION");
        } finally {
            vault.lock("test");
            try (Connection c = VaultFakes.admin(postgres); Statement s = c.createStatement()) {
                s.execute("ALTER DATABASE " + VaultFakes.SCRATCH_DATABASE + " REFRESH COLLATION VERSION");
            }
        }
        vault.unlock(PASSWORD);
        assertThat(status().get("collation").isNull()).isTrue();
    }

    @Test
    void theStatusNeverNamesAPathOrASecret() throws Exception {
        String key = vault.setup(PASSWORD);
        String unlocked = mvc.perform(get("/api/status")).andReturn().getResponse().getContentAsString();
        vault.lock("test");
        String locked = mvc.perform(get("/api/status")).andReturn().getResponse().getContentAsString();

        for (String body : new String[] {unlocked, locked}) {
            assertThat(body).doesNotContain(properties.vaultDir().toString()).doesNotContain("vault-test-")
                    .doesNotContain(PASSWORD).doesNotContain(key).contains("\"storage\":\"VAULT\"");
        }
        assertThat(json.readTree(locked).get("vault").get("state").asString()).isEqualTo("LOCKED");
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private MockHttpServletResponse postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
    }

    private int get200(String path) throws Exception {
        return mvc.perform(get(path)).andReturn().getResponse().getStatus();
    }

    private String problemType(MockHttpServletResponse response) throws Exception {
        return json.readTree(response.getContentAsString()).get("type").asString();
    }

    private JsonNode status() throws Exception {
        return json.readTree(mvc.perform(get("/api/status")).andReturn().getResponse().getContentAsString())
                .get("vault");
    }

    /** The pool's connections to the scratch database, from pg_stat_activity. */
    private int poolConnections() throws Exception {
        return Integer.parseInt(adminScalar("SELECT count(*) FROM pg_stat_activity WHERE datname = '"
                + VaultFakes.SCRATCH_DATABASE + "' AND application_name = '" + VaultDatabase.APPLICATION_NAME + "'"));
    }

    private String scalar(String sql) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(VaultFakes.scratchUrl(postgres),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void execute(String sql) throws Exception {
        try (Connection c = java.sql.DriverManager.getConnection(VaultFakes.scratchUrl(postgres),
                postgres.getUsername(), postgres.getPassword()); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private String adminScalar(String sql) throws Exception {
        try (Connection c = VaultFakes.admin(postgres); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
