package com.finalysis.ingestion.pdf;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The import's PDF extractor, with the approved limits (docs/plans/phase-3b.md §2.1). */
@Configuration
class PdfExtractionConfig {

    @Bean
    PdfTextExtractor pdfTextExtractor() {
        return new PdfTextExtractor(PdfLimits.DEFAULT);
    }
}
