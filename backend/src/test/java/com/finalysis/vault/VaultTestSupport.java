package com.finalysis.vault;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Runs the vault scripts ({@code scripts/vault/}, docs/plans/phase-8.md §4.3, §4.4) on throwaway vaults only: every
 * vault lives in a new temporary directory (its name has a space, as {@code Application Support} does), with a
 * synthetic key, and is stopped, unmounted and deleted afterwards. The owner's real vault is never named.
 *
 * <p>Every run points the conflict checks of {@code db-check.sh} at temporary files, so a test never reads this
 * machine's launchd jobs or Postgres.app's settings.
 */
final class VaultTestSupport {

    static final Path SCRIPTS = Path.of("..", "scripts", "vault").toAbsolutePath().normalize();
    static final Path POSTGRES_APP_BIN = Path.of("/Applications/Postgres.app/Contents/Versions/17/bin");
    private static final SecureRandom RANDOM = new SecureRandom();

    record Result(int exit, String out, String err) {
        String all() {
            return out + err;
        }
    }

    private VaultTestSupport() {
    }

    static boolean onMacWithHdiutil() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac")
                && Files.isExecutable(Path.of("/usr/bin/hdiutil"));
    }

    static String syntheticKey() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "synthetic-test-key-" + HexFormat.of().formatHex(bytes);
    }

    static String syntheticPassword() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Runs a script with the given stdin (none when null) and extra environment, waiting up to 3 minutes. */
    static Result run(Path isolation, String script, String stdin, Map<String, String> env, String... args) {
        List<String> command = new ArrayList<>();
        command.add(SCRIPTS.resolve(script).toString());
        command.addAll(List.of(args));
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("FINALYSIS_LAUNCHD_DIRS", isolation.resolve("launchd").toString());
        builder.environment().put("FINALYSIS_POSTGRESAPP_PREFS", isolation.resolve("postgresapp.plist").toString());
        builder.environment().putAll(env);
        try {
            Process process = builder.start();
            CompletableFuture<String> out = drain(process.getInputStream());
            CompletableFuture<String> err = drain(process.getErrorStream());
            try (OutputStream in = process.getOutputStream()) {
                if (stdin != null) {
                    in.write(stdin.getBytes(UTF_8));
                }
            }
            if (!process.waitFor(3, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException(script + " did not finish in 3 minutes");
            }
            return new Result(process.exitValue(), out.join(), err.join());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static CompletableFuture<String> drain(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** A throwaway vault directory, with empty stand-ins for the launchd and Postgres.app settings. */
    static final class TempVault implements AutoCloseable {

        final Path root;
        final Path dir;
        final String key = syntheticKey();

        TempVault() {
            try {
                root = Files.createTempDirectory("finalysis vault-test ").toRealPath();
                dir = Files.createDirectory(root.resolve("vault"));
                Files.createDirectory(root.resolve("launchd"));
                Files.writeString(root.resolve("postgresapp.plist"), emptyPlist());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        Result run(String script, String stdin, String... args) {
            return VaultTestSupport.run(root, script, stdin, Map.of(), args);
        }

        Result create(String size) {
            return run("create.sh", key + "\n", dir.toString(), size);
        }

        Result mount() {
            return run("mount.sh", key + "\n", dir.toString());
        }

        Result unmount() {
            return run("unmount.sh", null, dir.toString());
        }

        String status() {
            return run("status.sh", null, dir.toString()).out().strip();
        }

        Path mnt() {
            return dir.resolve("mnt");
        }

        Path bundle() {
            return dir.resolve("finalysis.sparsebundle");
        }

        /** Stops a server left running, unmounts (forced if need be) and deletes everything. */
        @Override
        public void close() {
            if (Files.exists(mnt().resolve("pgdata").resolve("postmaster.pid"))
                    && Files.isExecutable(POSTGRES_APP_BIN.resolve("pg_ctl"))) {
                run("db-stop.sh", null, dir.toString(), POSTGRES_APP_BIN.toString());
            }
            String state = status();
            if (!"UNMOUNTED".equals(state) && !"NO_IMAGE".equals(state)) {
                int exit = unmount().exit();
                if (exit != 0 && exit != 3) {
                    exec("/usr/bin/hdiutil", "detach", "-force", mnt().toString());
                }
            }
            if ("MOUNTED".equals(status()) || "ELSEWHERE".equals(status())) {
                throw new IllegalStateException("the test vault is still mounted: " + mnt());
            }
            deleteRecursively(root);
        }
    }

    static String emptyPlist() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                <plist version="1.0"><dict/></plist>
                """;
    }

    /** True when any file under {@code root} holds {@code marker} as ASCII/UTF-8 or as UTF-16LE. */
    static boolean anyFileContains(Path root, String marker) {
        byte[] ascii = marker.getBytes(UTF_8);
        byte[] utf16 = marker.getBytes(StandardCharsets.UTF_16LE);
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).anyMatch(file -> {
                try {
                    byte[] bytes = Files.readAllBytes(file);
                    return indexOf(bytes, ascii) >= 0 || indexOf(bytes, utf16) >= 0;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Samples every process's arguments ({@code ps}) until closed, and remembers whether any of them ever held one
     * of the secrets: a key or password must never be on a command line.
     */
    static final class ArgumentWatch implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean seen = new AtomicBoolean(false);
        private final List<String> secrets;
        private final Thread thread;
        private volatile int samples;

        ArgumentWatch(String... secrets) {
            this.secrets = List.of(secrets);
            this.thread = Thread.ofPlatform().daemon().start(this::sample);
        }

        private void sample() {
            while (running.get()) {
                String args = exec("/bin/ps", "-axww", "-o", "args=");
                // The ps process itself never holds a secret: it gets none.
                if (secrets.stream().anyMatch(args::contains)) {
                    seen.set(true);
                }
                samples++;
            }
        }

        boolean secretSeen() {
            return seen.get();
        }

        int samples() {
            return samples;
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            thread.join();
        }
    }

    static String exec(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), UTF_8);
            process.waitFor(1, TimeUnit.MINUTES);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
