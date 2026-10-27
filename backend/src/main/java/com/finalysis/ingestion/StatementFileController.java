package com.finalysis.ingestion;

import com.finalysis.api.InvalidField;
import com.finalysis.api.InvalidRequestException;
import com.finalysis.ingestion.StatementFileSettings.AfterImport;
import java.io.IOException;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Kept statement files and their default after import (docs/plans/phase-8.md §7). */
@RestController
public class StatementFileController {

    public record KeptFile(long statementId, boolean fileKept) {
    }

    public record ImportSettings(String afterImport) {
    }

    private final StatementFileStore store;
    private final StatementFileSettings settings;

    StatementFileController(StatementFileStore store, StatementFileSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    /** Keeps a copy of the statement's file in the vault: the same file again (multipart {@code file}). */
    @PutMapping(path = "/api/statements/{id}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KeptFile keep(@PathVariable long id, @RequestPart("file") MultipartFile file) throws IOException {
        UploadCheck.checkSize(file.getSize());
        store.keep(id, file.getBytes());
        return new KeptFile(id, true);
    }

    @GetMapping("/api/statements/{id}/file")
    public KeptFile kept(@PathVariable long id) {
        return new KeptFile(id, store.isKept(id));
    }

    @GetMapping("/api/imports/settings")
    public ImportSettings importSettings() {
        return new ImportSettings(settings.afterImport().name());
    }

    @PutMapping("/api/imports/settings")
    public ResponseEntity<Void> saveImportSettings(@RequestBody ImportSettings request) {
        AfterImport choice;
        try {
            choice = AfterImport.valueOf(String.valueOf(request.afterImport()));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(List.of(new InvalidField("afterImport",
                    "must be one of ASK, KEEP, DELETE, BOTH, LEAVE")));
        }
        settings.setAfterImport(choice);
        return ResponseEntity.noContent().build();
    }
}
