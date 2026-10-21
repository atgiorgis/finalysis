/**
 * What each chat tool sends to the model (docs/plans/phase-6.md §12): one record per tool result, built from the Phase 5
 * records by an explicit projection, so a field not copied is not sent. Every component has an {@link
 * com.finalysis.chat.tool.view.Outbound} kind or is itself a view record. Money, percents and dates are already text, as
 * the API writes them. Accounts are aliases, merchants their outbound text, rows are referred to by per-turn refs; no
 * id, balance, name, raw description or statement text exists in any of these types.
 */
package com.finalysis.chat.tool.view;
