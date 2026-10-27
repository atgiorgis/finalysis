package com.finalysis.vault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.finalysis.vault.VaultTestSupport.ArgumentWatch;
import com.finalysis.vault.VaultTestSupport.Result;
import com.finalysis.vault.VaultTestSupport.TempVault;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The disk-image scripts (create, mount, unmount, status; docs/plans/phase-8.md §4.3, §10) on a real 64 MB encrypted
 * image in a temporary directory, with a synthetic key. macOS only (hdiutil); skipped elsewhere. No Postgres needed:
 * the database scripts are covered by {@code VaultDatabaseIT}.
 */
class VaultScriptsTest {

    @BeforeEach
    void macOsOnly() {
        assumeTrue(VaultTestSupport.onMacWithHdiutil(), "the vault scripts need macOS's hdiutil");
    }

    @Test
    void createMountWriteUnmountLeavesNoReadableMarkerAndAnEmptyMountPoint() throws Exception {
        try (TempVault vault = new TempVault()) {
            assertThat(vault.status()).isEqualTo("NO_IMAGE");

            Result created;
            Result mounted;
            try (ArgumentWatch watch = new ArgumentWatch(vault.key)) {
                created = vault.create("64m");
                assertThat(vault.status()).isEqualTo("UNMOUNTED");
                mounted = vault.mount();
                assertThat(watch.samples()).isPositive();
                assertThat(watch.secretSeen()).as("the key was on a command line").isFalse();
            }
            assertThat(created.exit()).as(created.all()).isZero();
            assertThat(mounted.exit()).as(mounted.all()).isZero();
            assertThat(vault.status()).isEqualTo("MOUNTED");

            assertThat(Files.isDirectory(vault.bundle())).isTrue();
            try (Stream<Path> entries = Files.list(vault.mnt())) {
                assertThat(entries.map(p -> p.getFileName().toString())).contains(
                        "pgdata", "pglog", "secrets", "statements", "tls", ".metadata_never_index");
            }
            for (String folder : new String[] {"pgdata", "pglog", "secrets", "statements", "tls"}) {
                assertThat(Files.getPosixFilePermissions(vault.mnt().resolve(folder)))
                        .as(folder).hasSize(3); // rwx------
            }

            String marker = "MARKER-" + VaultTestSupport.syntheticKey();
            Files.writeString(vault.mnt().resolve("statements").resolve("marker.txt"), marker.repeat(50));

            Result unmounted = vault.unmount();
            assertThat(unmounted.exit()).as(unmounted.all()).isZero();
            assertThat(unmounted.out()).contains("unmount: done").doesNotContain("forced");
            assertThat(vault.status()).isEqualTo("UNMOUNTED");
            try (Stream<Path> entries = Files.list(vault.mnt())) {
                assertThat(entries).isEmpty();
            }
            assertThat(VaultTestSupport.anyFileContains(vault.bundle(), marker)).isFalse();
            assertThat(VaultTestSupport.anyFileContains(vault.dir, vault.key)).isFalse();

            // The data comes back with the key.
            assertThat(vault.mount().exit()).isZero();
            assertThat(Files.readString(vault.mnt().resolve("statements").resolve("marker.txt")))
                    .isEqualTo(marker.repeat(50));
            assertThat(vault.unmount().exit()).isZero();
        }
    }

    @Test
    void aWrongKeyMountsNothing() {
        try (TempVault vault = new TempVault()) {
            assertThat(vault.create("64m").exit()).isZero();

            Result wrong = vault.run("mount.sh", "not-the-key\n", vault.dir.toString());

            assertThat(wrong.exit()).isEqualTo(1);
            assertThat(wrong.err()).contains("wrong key");
            assertThat(vault.status()).isEqualTo("UNMOUNTED");
        }
    }

    @Test
    void createNeverReplacesAnExistingImage() {
        try (TempVault vault = new TempVault()) {
            assertThat(vault.create("64m").exit()).isZero();

            Result again = vault.create("64m");

            assertThat(again.exit()).isEqualTo(1);
            assertThat(again.err()).contains("an image already exists");
        }
    }

    @Test
    void unmountRefusesWhileAPostgresServerRunsOnTheVolume() throws IOException {
        try (TempVault vault = new TempVault()) {
            assertThat(vault.create("64m").exit()).isZero();
            assertThat(vault.mount().exit()).isZero();
            // A postmaster.pid naming a live process (this JVM) stands in for a running server.
            Path pid = vault.mnt().resolve("pgdata").resolve("postmaster.pid");
            Files.writeString(pid, ProcessHandle.current().pid() + "\n");

            Result refused = vault.unmount();

            assertThat(refused.exit()).isEqualTo(4);
            assertThat(vault.status()).isEqualTo("MOUNTED");
            Files.delete(pid);
            assertThat(vault.unmount().exit()).isZero();
        }
    }

    @Test
    void mountRefusesAMountedImageAndUnmountOfAnUnmountedOneIsANoOp() {
        try (TempVault vault = new TempVault()) {
            assertThat(vault.create("64m").exit()).isZero();
            assertThat(vault.unmount().out()).contains("not mounted");
            assertThat(vault.mount().exit()).isZero();

            Result twice = vault.mount();

            assertThat(twice.exit()).isEqualTo(1);
            assertThat(twice.err()).contains("already mounted");
            assertThat(vault.unmount().exit()).isZero();
        }
    }

    @Test
    void argumentsAndStdinAreChecked() {
        try (TempVault vault = new TempVault()) {
            String dir = vault.dir.toString();
            assertThat(vault.run("status.sh", null).exit()).isEqualTo(1);
            assertThat(vault.run("create.sh", vault.key, "relative/dir", "64m").err()).contains("absolute path");
            assertThat(vault.run("create.sh", vault.key, dir, "lots").err()).contains("64m or 50g");
            assertThat(vault.run("create.sh", vault.key, dir + "/missing", "64m").err()).contains("does not exist");
            assertThat(vault.run("create.sh", "", dir, "64m").err()).contains("no secret on stdin");
            assertThat(vault.run("mount.sh", vault.key, dir, "extra").exit()).isEqualTo(1);
            assertThat(vault.run("mount.sh", vault.key, dir).err()).contains("no image");
            assertThat(vault.status()).isEqualTo("NO_IMAGE");
        }
    }
}
