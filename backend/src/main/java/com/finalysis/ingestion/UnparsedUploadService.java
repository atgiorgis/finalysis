package com.finalysis.ingestion;

import com.finalysis.api.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reading and dismissing unparsed uploads (ING-13, docs/plans/phase-3c.md §4.3). Dismissing is one
 * conditional update, so it needs no account lock: an import that resolves the same upload updates the
 * same row, and whichever commits second finds it no longer open. Logs carry ids only (SEC-10).
 */
@Service
public class UnparsedUploadService {

    private static final Logger log = LoggerFactory.getLogger(UnparsedUploadService.class);

    private final UnparsedUploadRepository uploads;

    public UnparsedUploadService(UnparsedUploadRepository uploads) {
        this.uploads = uploads;
    }

    @Transactional(readOnly = true)
    public UnparsedUploadResponse get(long id) {
        return UnparsedUploadResponse.from(find(id));
    }

    /**
     * Dismisses an open upload. A re-upload of the same file afterwards opens a new row (V8).
     *
     * @throws UploadAlreadyResolvedException if it is already resolved or dismissed
     */
    @Transactional
    public UnparsedUploadResponse dismiss(long id) {
        find(id); // 404 first
        if (uploads.dismissOpen(id) == 0) {
            throw new UploadAlreadyResolvedException(id, UnparsedUploadState.of(find(id))); // re-read: cleared
        }
        log.info("Dismissed unparsed upload {}", id);
        return UnparsedUploadResponse.from(find(id));
    }

    private UnparsedUpload find(long id) {
        return uploads.findById(id).orElseThrow(() -> new NotFoundException("Unparsed upload", id));
    }
}
