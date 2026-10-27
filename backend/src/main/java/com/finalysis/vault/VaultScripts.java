package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs {@code scripts/vault/} (docs/plans/phase-8.md §4.3, §4.4). A secret goes to the script's stdin and its bytes
 * are zeroed once written; it is never an argument or an environment variable. Only the script's name and exit code
 * are ever logged, never its output.
 */
final class VaultScripts {

    private static final Logger log = LoggerFactory.getLogger(VaultScripts.class);
    private static final long TIMEOUT_MINUTES = 5;
    private static final int MAX_OUTPUT = 4096;

    record Result(int exit, String out) {
    }

    private final Path dir;

    /** The folder is looked at only when a script runs, so the app starts with the vault off from anywhere. */
    VaultScripts(Path scripts) {
        this.dir = scripts.toAbsolutePath().normalize();
    }

    /**
     * Runs {@code script} with {@code args}; {@code secret}, when given, goes to stdin followed by a newline and is
     * zeroed afterwards.
     */
    Result run(String script, byte[] secret, String... args) {
        Path executable = dir.resolve(script);
        if (!Files.isExecutable(executable)) {
            log.error("vault script {} is missing: finalysis.vault.scripts must point at scripts/vault/", script);
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
            return new Result(-1, "");
        }
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process = null;
        try {
            process = builder.start();
            CompletableFuture<String> out = drain(process.getInputStream());
            try (OutputStream in = process.getOutputStream()) {
                if (secret != null) {
                    in.write(secret);
                    in.write('\n');
                }
            } finally {
                if (secret != null) {
                    Arrays.fill(secret, (byte) 0);
                }
            }
            if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                log.error("vault script {} did not finish in {} minutes", script, TIMEOUT_MINUTES);
                return new Result(-1, "");
            }
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("vault script {} exited {}", script, exit);
            }
            return new Result(exit, out.join());
        } catch (IOException e) {
            log.error("vault script {} could not run ({})", script, e.getClass().getName());
            return new Result(-1, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new Result(-1, "");
        } finally {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
            }
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                byte[] bytes = stream.readAllBytes();
                return new String(bytes, 0, Math.min(bytes.length, MAX_OUTPUT), UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** The chars as UTF-8 bytes, for stdin; the caller's chars are left as they are. */
    static byte[] bytes(char[] chars) {
        java.nio.ByteBuffer buffer = UTF_8.encode(java.nio.CharBuffer.wrap(chars));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        if (buffer.hasArray()) {
            Arrays.fill(buffer.array(), (byte) 0);
        }
        return bytes;
    }
}
