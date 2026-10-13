package com.finalysis.ingestion;

import java.io.IOException;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
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

/**
 * Statement import (ING-01, ING-03, ING-04), import preview and statement detail. An import answers
 * 201 Created with the {@link ImportSummary}, or 202 Accepted with an {@link UnparsedUploadResponse}
 * for a PDF no layout parser recognized (ING-13).
 */
@RestController
public class StatementController {

    private final StatementImportService imports;

    public StatementController(StatementImportService imports) {
        this.imports = imports;
    }

    @PostMapping(path = "/api/accounts/{accountId}/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> importStatement(
            @PathVariable long accountId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            // Money as text such as "1234.56", parsed strictly (no exponents, at most two decimals).
            @RequestParam(required = false) String openingBalance,
            @RequestParam(required = false) String closingBalance) throws IOException {
        // Size first, before the bytes are copied; the multipart limit normally rejects larger files earlier.
        UploadCheck.checkSize(file.getSize());
        ImportOutcome outcome = imports.importStatement(new ImportRequest(accountId, file.getOriginalFilename(),
                file.getBytes(), periodStart, periodEnd, openingBalance, closingBalance));
        return switch (outcome) {
            case ImportOutcome.Imported(ImportSummary summary) -> {
                URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                        .path("/api/statements/{id}").buildAndExpand(summary.statementId()).toUri();
                yield ResponseEntity.created(location).body(summary);
            }
            // No Location: the review-queue API that reads unparsed uploads comes in 3c.
            case ImportOutcome.Unparsed(UnparsedUploadResponse upload) -> ResponseEntity.accepted().body(upload);
        };
    }

    /** Reads the file as an import would and writes nothing; see {@link ImportPreview}. */
    @PostMapping(path = "/api/accounts/{accountId}/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(@PathVariable long accountId, @RequestPart("file") MultipartFile file)
            throws IOException {
        UploadCheck.checkSize(file.getSize());
        return imports.preview(accountId, file.getOriginalFilename(), file.getBytes());
    }

    /** The account's statements, newest period first; {@code status} filters (ING-07's queue comes in 3c). */
    @GetMapping("/api/accounts/{accountId}/statements")
    public List<StatementListItem> list(@PathVariable long accountId,
                                        @RequestParam(required = false) StatementStatus status) {
        return imports.list(accountId, status);
    }

    @GetMapping("/api/statements/{id}")
    public StatementResponse get(@PathVariable long id) {
        return imports.get(id);
    }
}
