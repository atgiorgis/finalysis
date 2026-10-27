package com.finalysis.vault;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

/** The disk image through {@code create.sh}, {@code mount.sh}, {@code unmount.sh} and {@code status.sh} (§4.3). */
final class HdiutilVaultMount implements VaultMount {

    static final String BUNDLE = "finalysis.sparsebundle";

    private final VaultScripts scripts;
    private final VaultProperties properties;

    HdiutilVaultMount(VaultScripts scripts, VaultProperties properties) {
        this.scripts = scripts;
        this.properties = properties;
    }

    @Override
    public State state() {
        if (!Files.isDirectory(properties.vaultDir())) {
            return State.NO_IMAGE;
        }
        VaultScripts.Result result = scripts.run("status.sh", null, dir());
        return switch (result.out().strip()) {
            case "NO_IMAGE" -> State.NO_IMAGE;
            case "UNMOUNTED" -> State.UNMOUNTED;
            case "MOUNTED" -> State.MOUNTED;
            case "ELSEWHERE" -> State.ELSEWHERE;
            default -> throw new MountException("status.sh did not report a state");
        };
    }

    @Override
    public void create(char[] passphrase) {
        try {
            Files.createDirectories(properties.vaultDir(),
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (IOException e) {
            throw new MountException("the vault directory could not be created");
        }
        if (scripts.run("create.sh", VaultScripts.bytes(passphrase), dir(), properties.imageSize()).exit() != 0) {
            throw new MountException("create.sh failed");
        }
    }

    @Override
    public void mount(char[] passphrase) {
        VaultScripts.Result result = scripts.run("mount.sh", VaultScripts.bytes(passphrase), dir());
        if (result.exit() == 1 && result.out().contains("wrong key")) {
            throw new WrongKeyException();
        }
        if (result.exit() != 0) {
            throw new MountException("mount.sh failed");
        }
    }

    @Override
    public Unmounted unmount() {
        int exit = scripts.run("unmount.sh", null, dir()).exit();
        return switch (exit) {
            case 0 -> Unmounted.PLAIN;
            case 3 -> Unmounted.FORCED;
            case 4 -> throw new MountException("a Postgres server still runs on the volume");
            default -> throw new MountException("unmount.sh failed");
        };
    }

    @Override
    public void deleteNewImage() {
        Path bundle = properties.vaultDir().resolve(BUNDLE);
        if (!Files.exists(bundle)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(bundle)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path mountPoint() {
        return properties.mountPoint();
    }

    private String dir() {
        return properties.vaultDir().toString();
    }
}
