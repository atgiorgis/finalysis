package com.finalysis.ingestion;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unparsed uploads (ING-13): read one, or dismiss it so it leaves the review queue. */
@RestController
@RequestMapping("/api/unparsed-uploads")
public class UnparsedUploadController {

    private final UnparsedUploadService uploads;

    public UnparsedUploadController(UnparsedUploadService uploads) {
        this.uploads = uploads;
    }

    @GetMapping("/{id}")
    public UnparsedUploadResponse get(@PathVariable long id) {
        return uploads.get(id);
    }

    @PostMapping("/{id}/dismiss")
    public UnparsedUploadResponse dismiss(@PathVariable long id) {
        return uploads.dismiss(id);
    }
}
