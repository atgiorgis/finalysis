package com.finalysis.chat;

import com.finalysis.chat.tool.DataNote;
import com.finalysis.chat.tool.ToolName;
import java.util.List;

/**
 * One executed tool call: the JSON the model receives (a checked result or a tool error) and what stays local.
 *
 * @param tool      the tool, or null when the model named none of them
 * @param json      what is sent back in the tool-response message, written by {@code ToolResultWriter} only
 * @param rows      rows the guard kept; 0 for an error
 * @param dataNotes the result's data-quality flags with account ids, for the client's {@code dataNotes}; local only
 * @param shortened whether rows were cut to keep the result under its size cap
 */
public record ToolOutcome(ToolName tool, ToolStatus status, String json, int rows, long millis,
                          List<DataNote> dataNotes, boolean shortened) {

    public ToolOutcome {
        dataNotes = List.copyOf(dataNotes);
    }
}
