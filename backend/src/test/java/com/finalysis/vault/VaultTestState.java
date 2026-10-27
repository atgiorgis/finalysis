package com.finalysis.vault;

import com.finalysis.vault.VaultFakes.FakeDatabaseRuntime;
import com.finalysis.vault.VaultFakes.FakeVaultMount;
import com.finalysis.vault.VaultFakes.VaultEvents;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

/** Puts the shared vault context back to "not set up" between tests. */
public final class VaultTestState {

    private VaultTestState() {
    }

    public static void reset(VaultService vault, VaultProperties properties, FakeVaultMount mount,
                      FakeDatabaseRuntime runtime, VaultEvents events) {
        mount.failUnmount = false;
        vault.lock("test reset");
        try {
            Files.deleteIfExists(properties.keyFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        mount.reset();
        runtime.reset();
        vault.resetForTests();
        vault.start();
        events.clear();
    }
}
