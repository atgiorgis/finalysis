package com.finalysis.chat.summary;

import com.finalysis.chat.ChatTurnResponse;
import com.finalysis.chat.citation.Citation;
import com.finalysis.chat.tool.DataNote;
import java.util.List;

/**
 * {@code POST /api/chat/summaries} (ANA-05, docs/plans/phase-6.md §11.1). Not stored: each request is a fresh computation.
 *
 * @param month     the month summarized; null when there is no statement data at all
 * @param source    {@code MODEL}, or {@code TEMPLATE} (AI off, no data, a limit, or the model's text ungrounded)
 * @param grounding the shown text's check: the template's too, which always grounds; {@code retried} when the model was
 *                  asked twice
 * @param dataNotes the month's data-quality flags and its alerts' flags, with local account ids
 * @param usage     the model calls made, or null when none was
 */
public record MonthlySummaryResponse(String month, Source source, String text, ChatTurnResponse.Grounding grounding,
                                     List<Citation> citations, List<DataNote> dataNotes,
                                     ChatTurnResponse.Usage usage) {

    public MonthlySummaryResponse {
        citations = List.copyOf(citations);
        dataNotes = List.copyOf(dataNotes);
    }

    public enum Source { MODEL, TEMPLATE }
}
