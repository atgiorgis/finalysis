package com.finalysis.ingestion;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/** Statement import (ING-01, ING-03, ING-04) and statement detail. */
@RestController
public class StatementController {

    private final StatementImportService imports;

    public StatementController(StatementImportService imports) {
        this.imports = imports;
    }

    @PostMapping(path = "/api/accounts/{accountId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportSummary> importStatement(
            @PathVariable long accountId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestParam(required = false) BigDecimal openingBalance,
            @RequestParam(required = false) BigDecimal closingBalance) throws IOException {
        // Size first, before the bytes are copied; the multipart limit normally rejects larger files earlier.
        UploadCheck.checkSize(file.getSize());
        ImportSummary summary = imports.importStatement(new ImportRequest(accountId, file.getOriginalFilename(),
                file.getBytes(), periodStart, periodEnd, openingBalance, closingBalance));
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/statements/{id}").buildAndExpand(summary.statementId()).toUri();
        return ResponseEntity.created(location).body(summary);
    }

    @GetMapping("/api/statements/{id}")
    public StatementResponse get(@PathVariable long id) {
        return imports.get(id);
    }
}
