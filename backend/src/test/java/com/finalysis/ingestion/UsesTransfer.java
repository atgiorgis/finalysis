package com.finalysis.ingestion;

import com.finalysis.transfer.TransferDetector;

/** A deliberate violation for {@code PackageDependencyTest}: ingestion code that depends on transfer. Test-only. */
@SuppressWarnings("unused")
public class UsesTransfer {
    private TransferDetector detector;
}
