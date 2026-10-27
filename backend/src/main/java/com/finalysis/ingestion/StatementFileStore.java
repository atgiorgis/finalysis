package com.finalysis.ingestion;

import com.finalysis.api.NotFoundException;
import com.finalysis.vault.VaultFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Kept statement files (docs/plans/phase-8.md §7, SEC-04): a copy of an imported file inside the vault, written only
 * when the user asks after the import. The browser sends the file again; it must be the very file the statement was
 * imported from (its SHA-256). Stored as {@code <statementId>-<first 12 hex of the hash>.<ext>}: the original file
 * name is never kept (it may hold an account number), the extension comes from the content and the parser. Mode 0600,
 * written through a temporary file and an atomic rename. Deleted with its statement. Logs ids and counts only.
 */
@Component
public class StatementFileStore {

    private static final Logger log = LoggerFactory.getLogger(StatementFileStore.class);

    /** The copy needs the vault: none with it off, nothing while locked (the gate refuses then anyway). */
    public static final class NoVaultException extends RuntimeException {
        NoVaultException() {
            super("Keeping a copy needs the encrypted vault, and it is off. Nothing was stored.", null, false, false);
        }
    }

    /** The bytes sent are not the file this statement was imported from. */
    public static final class FileMismatchException extends RuntimeException {
        FileMismatchException() {
            super("That file isn't the one this statement was imported from. Nothing was stored.", null, false, false);
        }
    }

    private final StatementRepository statements;
    private final VaultFiles vaultFiles;

    StatementFileStore(StatementRepository statements, VaultFiles vaultFiles) {
        this.statements = statements;
        this.vaultFiles = vaultFiles;
    }

    /** Keeps the statement's file in the vault; idempotent. */
    public void keep(long statementId, byte[] bytes) {
        Statement statement = statements.findById(statementId)
                .orElseThrow(() -> new NotFoundException("Statement", statementId));
        Path directory = vaultFiles.statementsDirectory().orElseThrow(NoVaultException::new);
        String sha256 = ImportFile.of("", bytes).sha256();
        if (!sha256.equals(statement.getFileHash())) {
            throw new FileMismatchException();
        }
        String name = statementId + "-" + sha256.substring(0, 12) + "." + extension(bytes, statement);
        Path target = directory.resolve(name);
        if (Files.exists(target)) {
            return;
        }
        Path temp = directory.resolve("." + statementId + ".tmp");
        try {
            Files.createDirectories(directory);
            Files.deleteIfExists(temp);
            Files.createFile(temp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            log.info("Kept the file of statement {} in the vault", statementId);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // the temporary file is inside the vault either way
            }
            throw new UncheckedIOException("the statement file could not be kept", e);
        }
    }

    /** Whether a copy of the statement's file is kept; false while the vault is locked or off. */
    public boolean isKept(long statementId) {
        return vaultFiles.statementsDirectory().map(directory -> !copies(directory, statementId).isEmpty())
                .orElse(false);
    }

    /** The statement is gone: so is its copy (§7). After commit, so a rolled-back delete keeps it. */
    @TransactionalEventListener // AFTER_COMMIT
    void onDeleted(StatementDeleted event) {
        vaultFiles.statementsDirectory().ifPresent(directory -> {
            int deleted = 0;
            for (Path copy : copies(directory, event.statementId())) {
                try {
                    Files.deleteIfExists(copy);
                    deleted++;
                } catch (IOException e) {
                    log.error("Deleting the kept file of statement {} failed ({})", event.statementId(),
                            e.getClass().getName());
                }
            }
            if (deleted > 0) {
                log.info("Deleted the kept file of statement {}", event.statementId());
            }
        });
    }

    private static java.util.List<Path> copies(Path directory, long statementId) {
        if (!Files.isDirectory(directory)) {
            return java.util.List.of();
        }
        java.util.List<Path> found = new java.util.ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, statementId + "-*")) {
            stream.forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    /** From the content and the parser, never from the original file name. */
    static String extension(byte[] bytes, Statement statement) {
        if (bytes.length >= 5 && bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F'
                && bytes[4] == '-') {
            return "pdf";
        }
        String parser = statement.getParser();
        return parser != null && parser.startsWith("csv") ? "csv" : "ofx";
    }
}
