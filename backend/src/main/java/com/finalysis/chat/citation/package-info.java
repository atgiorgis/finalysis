/**
 * Citations (CHT-05, docs/plans/phase-6.md §6): the refs an answer cites, resolved locally to txns with their statement
 * and source line, to the drill-downs behind a total, to alerts and recurring series. Read-only; nothing here is ever
 * sent to the model (the outbound packages may not depend on it, {@code CitationTest}).
 */
package com.finalysis.chat.citation;
