package com.finalysis.chat.summary;

import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/chat/summaries {month}} (ANA-05, docs/plans/phase-6.md §10.2, §11.1): a plain-English summary of one
 * month, by the model when AI is on, else by the template. 200 either way; 400 for a month that isn't {@code yyyy-MM},
 * 429 when the day's chat budget is spent, 502 when the model call fails.
 */
@RestController
@RequestMapping("/api/chat/summaries")
public class MonthlySummaryController {

    /** @param month {@code yyyy-MM}; omitted (or no body) for the latest complete month */
    public record SummaryRequest(String month) {
    }

    private final MonthlySummaryService summaries;

    public MonthlySummaryController(MonthlySummaryService summaries) {
        this.summaries = summaries;
    }

    @PostMapping
    public MonthlySummaryResponse summarize(@RequestBody(required = false) SummaryRequest request) {
        return summaries.summarize(Optional.ofNullable(request).map(SummaryRequest::month));
    }
}
