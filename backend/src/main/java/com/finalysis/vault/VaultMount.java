package com.finalysis.vault;

import java.nio.file.Path;

/**
 * The vault's encrypted disk image (docs/plans/phase-8.md §4.1, §4.3): {@link HdiutilVaultMount} on macOS; a fake in
 * tests. The passphrase is the recovery key's text; implementations never keep it.
 */
public interface VaultMount {

    enum State { NO_IMAGE, UNMOUNTED, MOUNTED, ELSEWHERE }

    enum Unmounted { PLAIN, FORCED }

    State state();

    /** Creates the image and its folders; never over an existing one. */
    void create(char[] passphrase);

    /** Mounts the image; throws {@link WrongKeyException} when the passphrase doesn't open it. */
    void mount(char[] passphrase);

    /** Unmounts the image: plainly, or forced after the plain attempts (reported). */
    Unmounted unmount();

    /** Deletes the image this call's setup just created, after a setup that failed partway. */
    void deleteNewImage();

    Path mountPoint();

    final class WrongKeyException extends RuntimeException {
        WrongKeyException() {
            super("the key does not open the image", null, false, false);
        }
    }

    final class MountException extends RuntimeException {
        MountException(String what) {
            super(what, null, false, false);
        }
    }
}
