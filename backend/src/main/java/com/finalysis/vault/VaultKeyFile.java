package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code vault-key.json} (docs/plans/phase-8.md §4.2): the recovery key wrapped with a key derived from the password
 * (Argon2id), by AES-256-GCM with a random 12-byte nonce. The header (format, KDF, its parameters, the salt) is the
 * associated data, so a changed parameter never unwraps. A file that doesn't parse, has an unknown format, or names
 * parameters outside the floor and ceiling is damaged; a failed tag is a wrong password. The two can't always be told
 * apart, so neither ever mounts anything.
 */
final class VaultKeyFile {

    static final int FORMAT = 1;
    private static final String KDF = "argon2id";
    private static final int ARGON2_VERSION = 0x13;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The file as stored. */
    record Contents(KdfParameters kdf, byte[] salt, byte[] nonce, byte[] wrapped) {
    }

    /** The JSON shape. */
    record Json(int format, String kdf, int version, int memoryKiB, int iterations, int parallelism, String salt,
                String nonce, String wrappedKey) {
    }

    static final class DamagedException extends RuntimeException {
        DamagedException() {
            super("the key file is damaged", null, false, false);
        }
    }

    static final class WrongPasswordException extends RuntimeException {
        WrongPasswordException() {
            super("the password does not unwrap the key", null, false, false);
        }
    }

    private VaultKeyFile() {
    }

    /** Wraps the recovery key's bytes with the password, under a new salt and nonce. */
    static Contents wrap(byte[] recoveryKey, char[] password, KdfParameters kdf, SecureRandom random) {
        byte[] salt = new byte[SALT_BYTES];
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(salt);
        random.nextBytes(nonce);
        byte[] key = kdf.derive(password, salt);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header(kdf, salt));
            return new Contents(kdf, salt, nonce, cipher.doFinal(recoveryKey));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is unavailable", e);
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** The recovery key's bytes; the caller zeroes them. */
    static byte[] unwrap(Contents contents, char[] password) {
        byte[] key = contents.kdf().derive(password, contents.salt());
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, contents.nonce()));
            cipher.updateAAD(header(contents.kdf(), contents.salt()));
            byte[] recoveryKey = cipher.doFinal(contents.wrapped());
            if (recoveryKey.length != RecoveryKey.BYTES) {
                Arrays.fill(recoveryKey, (byte) 0);
                throw new DamagedException();
            }
            return recoveryKey;
        } catch (AEADBadTagException e) {
            throw new WrongPasswordException();
        } catch (GeneralSecurityException e) {
            throw new DamagedException();
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    /** Reads the file; {@link DamagedException} for anything but a well-formed format-1 file within the bounds. */
    static Contents read(Path file, KdfParameters floor) {
        Json json;
        try {
            json = JSON.readValue(Files.readAllBytes(file), Json.class);
        } catch (IOException | RuntimeException e) { // JacksonException is a RuntimeException in Jackson 3
            throw new DamagedException();
        }
        if (json == null || json.format() != FORMAT || !KDF.equals(json.kdf()) || json.version() != ARGON2_VERSION) {
            throw new DamagedException();
        }
        KdfParameters kdf = new KdfParameters(json.memoryKiB(), json.iterations(), json.parallelism());
        if (!kdf.atLeast(floor) || !kdf.atMost(KdfParameters.CEILING)) {
            throw new DamagedException();
        }
        byte[] salt = decode(json.salt(), SALT_BYTES);
        byte[] nonce = decode(json.nonce(), NONCE_BYTES);
        byte[] wrapped = decode(json.wrappedKey(), RecoveryKey.BYTES + TAG_BITS / 8);
        return new Contents(kdf, salt, nonce, wrapped);
    }

    /** Writes the file through a temporary file, fsync and an atomic rename; mode 0600. */
    static void write(Path file, Contents contents) {
        Json json = new Json(FORMAT, KDF, ARGON2_VERSION, contents.kdf().memoryKiB(), contents.kdf().iterations(),
                contents.kdf().parallelism(), encode(contents.salt()), encode(contents.nonce()),
                encode(contents.wrapped()));
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.deleteIfExists(temp);
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(json);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic rename unavailable", e);
            }
            try (FileChannel dir = FileChannel.open(file.getParent(), StandardOpenOption.READ)) {
                dir.force(true);
            } catch (IOException ignored) {
                // Directories can't always be opened for fsync (macOS allows it); the rename itself is atomic.
            }
        } catch (IOException e) {
            throw new IllegalStateException("the key file could not be written", e);
        }
    }

    private static byte[] header(KdfParameters kdf, byte[] salt) {
        return ("finalysis-vault-key;format=" + FORMAT + ";kdf=" + KDF + ";v=" + ARGON2_VERSION + ";m="
                + kdf.memoryKiB() + ";t=" + kdf.iterations() + ";p=" + kdf.parallelism() + ";salt=" + encode(salt))
                .getBytes(UTF_8);
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String text, int length) {
        try {
            byte[] bytes = Base64.getDecoder().decode(text == null ? "" : text);
            if (bytes.length != length) {
                throw new DamagedException();
            }
            return bytes;
        } catch (IllegalArgumentException e) {
            throw new DamagedException();
        }
    }
}
