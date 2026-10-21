/**
 * Number grounding (CHT-04, docs/plans/phase-6.md §5): an answer is made plain text, its markers checked, every figure
 * in it found and matched against the figures of that turn's tool results as they were sent; an answer that stays
 * ungrounded after one retry is replaced by a Java-built list of the results. Pure Java over the outbound JSON and the
 * checked views: no model, no database.
 */
package com.finalysis.chat.grounding;
