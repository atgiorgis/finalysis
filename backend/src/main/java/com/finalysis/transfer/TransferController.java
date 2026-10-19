package com.finalysis.transfer;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Transfer actions over the API (ING-05, docs/plans/phase-3c.md §3, §4.5). User actions only: chatbot tools
 * never call these (SEC-03). "Not a transfer" ({@code /api/transactions/{id}/not-transfer}) is served by
 * {@code TransactionController} since Phase 4 step 9d, as it also clears a Transfer-kind category.
 *
 * <p>Link ids are digits only in the paths, so {@code /api/transfers/detect} never matches
 * {@code /api/transfers/{id}}: a GET there is a 405, not a failed id conversion.
 */
@RestController
public class TransferController {

    private final TransferService transfers;
    private final TransferDetector detector;

    public TransferController(TransferService transfers, TransferDetector detector) {
        this.transfers = transfers;
        this.detector = detector;
    }

    /** Newest effective date first; {@code accountId} matches either side. */
    @GetMapping("/api/transfers")
    public List<TransferLinkResponse> list(@RequestParam(required = false) TransferStatus status,
                                           @RequestParam(required = false) Boolean confirmed,
                                           @RequestParam(required = false) Long accountId) {
        return transfers.list(status, confirmed, accountId);
    }

    @GetMapping("/api/transfers/{id:\\d+}")
    public TransferLinkResponse get(@PathVariable long id) {
        return transfers.get(id);
    }

    /** Links two txns by hand, or marks one as a one-sided transfer. */
    @PostMapping("/api/transfers")
    public ResponseEntity<TransferLinkResponse> create(@RequestBody CreateTransferRequest request) {
        TransferLinkResponse created = transfers.create(request);
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/transfers/{id}").buildAndExpand(created.id()).toUri();
        return ResponseEntity.created(location).body(created);
    }

    /** Joins a possible match: two one-sided links become one confirmed link (§4.5). */
    @PostMapping("/api/transfers/join")
    public ResponseEntity<TransferLinkResponse> join(@Valid @RequestBody JoinTransferRequest request) {
        TransferLinkResponse joined = transfers.join(request.outLinkId(), request.inLinkId());
        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/transfers/{id}").buildAndExpand(joined.id()).toUri();
        return ResponseEntity.created(location).body(joined);
    }

    /** Dismisses a possible match: the pair is remembered as rejected; both one-sided links stay. */
    @PostMapping("/api/transfers/join/dismiss")
    public ResponseEntity<Void> dismissJoin(@Valid @RequestBody JoinTransferRequest request) {
        transfers.dismissJoin(request.outLinkId(), request.inLinkId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/transfers/{id:\\d+}/confirm")
    public TransferLinkResponse confirm(@PathVariable long id) {
        return transfers.confirm(id);
    }

    /** Unlinks; a MATCHED pair is remembered as rejected. */
    @DeleteMapping("/api/transfers/{id:\\d+}")
    public ResponseEntity<Void> unlink(@PathVariable long id) {
        transfers.unlink(id);
        return ResponseEntity.noContent().build();
    }

    /** Runs detection now, e.g. after a window change or after undoing "not a transfer". */
    @PostMapping("/api/transfers/detect")
    public DetectionResult detect() {
        return detector.detect();
    }
}
