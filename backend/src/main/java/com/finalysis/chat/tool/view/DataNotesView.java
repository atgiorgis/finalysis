package com.finalysis.chat.tool.view;

import java.util.List;

/**
 * The data-quality flags of the months a result used (docs/plans/phase-6.md §3.1), for the tools whose own view has
 * none: each flag's type, account alias and month.
 */
public record DataNotesView(List<FlagView> notes) {
}
