package com.finalysis.transaction;

import com.finalysis.categorize.CategorySource;
import com.finalysis.transfer.NotTransferResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Listing txns, correcting their categories (CAT-04; docs/plans/phase-4.md §9.2), and marking one not a transfer
 * (ING-05; here since step 9d, same paths, because it also clears a Transfer-kind category). User actions only:
 * chatbot tools never call the corrections or "not a transfer" (SEC-03).
 */
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactions;

    public TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    /** Newest {@code txn_date} first, paged; see {@link TransactionFilter} for the filters. */
    @GetMapping
    public TransactionPage list(@RequestParam(required = false) Long accountId,
                                @RequestParam(required = false) Long categoryId,
                                @RequestParam(required = false) Boolean uncategorized,
                                @RequestParam(required = false) CategorySource source,
                                @RequestParam(required = false) Boolean isTransfer,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(required = false) String merchant,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "" + TransactionFilter.DEFAULT_SIZE) int size) {
        return transactions.list(new TransactionFilter(accountId, categoryId, uncategorized, source, isTransfer, from, to,
                merchant, page, size));
    }

    @GetMapping("/{id:\\d+}")
    public TransactionResponse get(@PathVariable long id) {
        return transactions.get(id);
    }

    /** Sets a USER category; by default also upserts the merchant's correction rule (CAT-04). */
    @PutMapping("/{id:\\d+}/category")
    public CorrectionResponse correct(@PathVariable long id, @Valid @RequestBody CorrectCategoryRequest request) {
        return transactions.correct(id, request);
    }

    /**
     * Clears a USER category; the rules run for the txn now. Any other category is left as it is. With
     * {@code removeRule=true} it is a full undo: the merchant's correction rule is deleted too. Same body either way.
     */
    @DeleteMapping("/{id:\\d+}/category")
    public UndoResponse clearCorrection(@PathVariable long id,
                                        @RequestParam(defaultValue = "false") boolean removeRule) {
        return transactions.clearCorrection(id, removeRule);
    }

    /**
     * Marks the txn not a transfer: removes its link (rejecting a MATCHED pair), excludes it from detection, and clears
     * a RULE or AI category of Transfer kind.
     */
    @PostMapping("/{id}/not-transfer")
    public NotTransferResponse markNotTransfer(@PathVariable long id) {
        return transactions.markNotTransfer(id);
    }

    /** Clears "not a transfer"; the txn is eligible again at the next detection run and rules pass. */
    @DeleteMapping("/{id}/not-transfer")
    public ResponseEntity<Void> undoNotTransfer(@PathVariable long id) {
        transactions.undoNotTransfer(id);
        return ResponseEntity.noContent().build();
    }
}
