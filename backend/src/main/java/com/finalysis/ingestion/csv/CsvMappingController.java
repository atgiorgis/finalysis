package com.finalysis.ingestion.csv;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** An account's CSV column mapping (ING-01). */
@RestController
@RequestMapping("/api/accounts/{accountId}/csv-mapping")
public class CsvMappingController {

    private final CsvMappingService mappings;

    public CsvMappingController(CsvMappingService mappings) {
        this.mappings = mappings;
    }

    @GetMapping
    public CsvMappingResponse get(@PathVariable long accountId) {
        return mappings.get(accountId);
    }

    /** Creates (201) or replaces (200) the mapping; see {@link CsvMapping} for the fingerprint lock. */
    @PutMapping
    public ResponseEntity<CsvMappingResponse> put(
            @PathVariable long accountId,
            @Valid @RequestBody CsvMappingRequest request,
            @RequestParam(defaultValue = "false") boolean confirmFingerprintChange) {
        CsvMappingService.Saved saved = mappings.put(accountId, request, confirmFingerprintChange);
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK).body(saved.mapping());
    }
}
