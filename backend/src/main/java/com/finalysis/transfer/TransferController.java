package com.finalysis.transfer;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfer actions over the API (ING-05, docs/plans/phase-3c.md §3). User actions only: chatbot tools
 * never call these (SEC-03).
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferDetector detector;

    public TransferController(TransferDetector detector) {
        this.detector = detector;
    }

    /** Runs detection now, e.g. after a window change or after undoing "not a transfer". */
    @PostMapping("/detect")
    public DetectionResult detect() {
        return detector.detect();
    }
}
