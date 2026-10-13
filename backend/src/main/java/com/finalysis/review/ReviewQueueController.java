package com.finalysis.review;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue (ING-07, docs/plans/phase-3c.md §4.1): one list across accounts. Read-only; the
 * user resolves items with the statement, unparsed-upload and transfer actions.
 */
@RestController
public class ReviewQueueController {

    private final ReviewQueueService queue;

    public ReviewQueueController(ReviewQueueService queue) {
        this.queue = queue;
    }

    /** {@code accountId} matches an item touching that account; an unknown one gives an empty queue. */
    @GetMapping("/api/review-queue")
    public ReviewQueueResponse list(@RequestParam(required = false) ReviewItemKind kind,
                                    @RequestParam(required = false) Long accountId) {
        return queue.list(kind, accountId);
    }
}
