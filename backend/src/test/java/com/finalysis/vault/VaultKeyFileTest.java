package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Key wrapping (docs/plans/phase-8.md §4.2, §10 "Key wrapping"): Argon2id and AES-256-GCM round trip; a wrong password
 * never unwraps; every byte of the salt, nonce and wrapped key, flipped one at a time, never unwraps; a changed header
 * parameter never unwraps; an unparseable or out-of-bounds file is damaged; calibration stays within its bounds.
 * Cheap Argon2 parameters (8 MiB, one pass) except where the production floor is the point.
 */
class VaultKeyFileTest {

    private static final KdfParameters CHEAP = new KdfParameters(8 * 1024, 1, 1);
    private static final SecureRandom RANDOM = new SecureRandom();

    @TempDir
    Path dir;

    @Test
    void wrapsAndUnwrapsThroughTheFile() {
        byte[] recoveryKey = randomKey();
        VaultKeyFile.Contents contents = VaultKeyFile.wrap(recoveryKey, "a synthetic password".toCharArray(), CHEAP,
                RANDOM);
        Path file = dir.resolve("vault-key.json");

        VaultKeyFile.write(file, contents);
        VaultKeyFile.Contents read = VaultKeyFile.read(file, CHEAP);

        assertThat(VaultKeyFile.unwrap(read, "a synthetic password".toCharArray())).isEqualTo(recoveryKey);
        assertThat(read.kdf()).isEqualTo(CHEAP);
    }

    @Test
    void theFileIsOwnerOnlyAndHoldsNoSecret() throws IOException {
        byte[] recoveryKey = randomKey();
        Path file = dir.resolve("vault-key.json");
        VaultKeyFile.write(file, VaultKeyFile.wrap(recoveryKey, "a synthetic password".toCharArray(), CHEAP, RANDOM));

        assertThat(Files.getPosixFilePermissions(file))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        String text = Files.readString(file);
        assertThat(text).contains("\"format\"", "\"kdf\" : \"argon2id\"", "\"memoryKiB\"", "\"salt\"", "\"nonce\"")
                .doesNotContain("a synthetic password");
        try (RecoveryKey key = RecoveryKey.of(recoveryKey.clone())) {
            assertThat(text).doesNotContain(new String(key.passphrase()));
        }
        assertThat(dir.resolve("vault-key.json.tmp")).doesNotExist();
    }

    @Test
    void aWrongPasswordNeverUnwraps() {
        VaultKeyFile.Contents contents = VaultKeyFile.wrap(randomKey(), "the right password".toCharArray(), CHEAP,
                RANDOM);

        assertThatThrownBy(() -> VaultKeyFile.unwrap(contents, "the wrong password".toCharArray()))
                .isInstanceOf(VaultKeyFile.WrongPasswordException.class);
        assertThatThrownBy(() -> VaultKeyFile.unwrap(contents, new char[0]))
                .isInstanceOf(VaultKeyFile.WrongPasswordException.class);
    }

    @Test
    void everyByteFlippedNeverUnwraps() {
        char[] password = "the right password".toCharArray();
        VaultKeyFile.Contents contents = VaultKeyFile.wrap(randomKey(), password, CHEAP, RANDOM);
        int tried = 0;
        for (int part = 0; part < 3; part++) {
            byte[] original = switch (part) {
                case 0 -> contents.salt();
                case 1 -> contents.nonce();
                default -> contents.wrapped();
            };
            for (int i = 0; i < original.length; i++) {
                byte[] flipped = original.clone();
                flipped[i] ^= 0x01;
                VaultKeyFile.Contents changed = switch (part) {
                    case 0 -> new VaultKeyFile.Contents(contents.kdf(), flipped, contents.nonce(), contents.wrapped());
                    case 1 -> new VaultKeyFile.Contents(contents.kdf(), contents.salt(), flipped, contents.wrapped());
                    default -> new VaultKeyFile.Contents(contents.kdf(), contents.salt(), contents.nonce(), flipped);
                };
                assertThatThrownBy(() -> VaultKeyFile.unwrap(changed, password))
                        .as("part %d byte %d", part, i).isInstanceOf(VaultKeyFile.WrongPasswordException.class);
                tried++;
            }
        }
        assertThat(tried).isEqualTo(16 + 12 + 48);
    }

    @Test
    void aChangedHeaderParameterNeverUnwraps() throws IOException {
        char[] password = "the right password".toCharArray();
        Path file = dir.resolve("vault-key.json");
        VaultKeyFile.write(file, VaultKeyFile.wrap(randomKey(), password, CHEAP, RANDOM));
        String text = Files.readString(file);

        for (String[] change : new String[][] {
                {"\"iterations\" : 1", "\"iterations\" : 2"},
                {"\"memoryKiB\" : 8192", "\"memoryKiB\" : 9216"},
                {"\"parallelism\" : 1", "\"parallelism\" : 2"}}) {
            assertThat(text).contains(change[0]);
            Files.writeString(file, text.replace(change[0], change[1]));
            VaultKeyFile.Contents changed = VaultKeyFile.read(file, CHEAP);
            assertThatThrownBy(() -> VaultKeyFile.unwrap(changed, password)).as(change[1])
                    .isInstanceOf(VaultKeyFile.WrongPasswordException.class);
        }
    }

    @Test
    void anUnparseableOrUnknownOrOutOfBoundsFileIsDamaged() throws IOException {
        Path file = dir.resolve("vault-key.json");
        VaultKeyFile.write(file, VaultKeyFile.wrap(randomKey(), "pw for the damage test".toCharArray(), CHEAP,
                RANDOM));
        String good = Files.readString(file);

        for (String bad : new String[] {
                "", "{", "not json", "[]", "{}", good.replace("\"format\" : 1", "\"format\" : 2"),
                good.replace("argon2id", "scrypt"), good.replace("\"version\" : 19", "\"version\" : 16"),
                good.replace("\"memoryKiB\" : 8192", "\"memoryKiB\" : 1024"),
                good.replaceFirst("\"salt\" : \"[^\"]+\"", "\"salt\" : \"AAAA\""),
                good.replaceFirst("\"nonce\" : \"[^\"]+\"", "\"nonce\" : \"!!not base64!!\""),
                good.replaceFirst("\"wrappedKey\" : \"[^\"]+\"", "\"wrappedKey\" : \"\"")}) {
            Files.writeString(file, bad);
            assertThatThrownBy(() -> VaultKeyFile.read(file, CHEAP)).as(bad)
                    .isInstanceOf(VaultKeyFile.DamagedException.class);
        }
        Files.delete(file);
        assertThatThrownBy(() -> VaultKeyFile.read(file, CHEAP)).isInstanceOf(VaultKeyFile.DamagedException.class);
    }

    @Test
    void aFileBelowTheProductionFloorIsDamagedInProduction() {
        Path file = dir.resolve("vault-key.json");
        VaultKeyFile.write(file, VaultKeyFile.wrap(randomKey(), "pw".toCharArray(), CHEAP, RANDOM));

        assertThatThrownBy(() -> VaultKeyFile.read(file, KdfParameters.FLOOR))
                .isInstanceOf(VaultKeyFile.DamagedException.class);
    }

    @Test
    void calibrationStaysWithinTheFloorAndTheCap() {
        KdfParameters calibrated = new KdfPolicy().calibrate();

        assertThat(calibrated.atLeast(KdfParameters.FLOOR)).isTrue();
        assertThat(calibrated.memoryKiB()).isEqualTo(64 * 1024);
        assertThat(calibrated.parallelism()).isEqualTo(1);
        assertThat(calibrated.iterations()).isBetween(3, 12);
    }

    @Test
    void theRealFloorRoundTrips() {
        byte[] recoveryKey = randomKey();
        VaultKeyFile.Contents contents = VaultKeyFile.wrap(recoveryKey, "production cost".toCharArray(),
                KdfParameters.FLOOR, RANDOM);
        assertThat(VaultKeyFile.unwrap(contents, "production cost".toCharArray())).isEqualTo(recoveryKey);
    }

    /**
     * Our derivation against an independent implementation (argon2-cffi 23.1.0, the reference C code), so a Bouncy
     * Castle upgrade that changed the output, which would lock every vault, fails here first.
     */
    @Test
    void argon2idMatchesAnIndependentImplementation() {
        byte[] salt = new byte[16];
        Arrays.fill(salt, (byte) 0x02);
        byte[] key = new KdfParameters(8 * 1024, 1, 1).derive("password".toCharArray(), salt);
        assertThat(java.util.HexFormat.of().formatHex(key)).isEqualTo(KNOWN_ANSWER);
    }

    /**
     * Argon2id v1.3, m=8192 KiB, t=1, p=1, password "password", salt 16 x 0x02, 32-byte output; computed with
     * argon2-cffi's {@code hash_secret_raw}.
     */
    static final String KNOWN_ANSWER = "a0cc0d84d6376d733e3b12f9ea54102aebbe90255da61d65807de50265698e23";

    private static byte[] randomKey() {
        byte[] key = new byte[RecoveryKey.BYTES];
        RANDOM.nextBytes(key);
        return key;
    }
}
