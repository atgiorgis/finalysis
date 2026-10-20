/**
 * The chat tools (docs/plans/phase-6.md §3): thin, read-only adapters over the Phase 5 query services, with closed
 * parameters, per-turn refs instead of ids, bounded results and outbound projections. A tool never computes a figure.
 *
 * <p>Classes here may depend only on the analytics and alert query services, their records and enums, the shared
 * {@code api} types, Spring's core, transaction and JDBC support, Jackson and the JDK; never on Spring AI, a writer,
 * or the conversation store (§9, {@code ChatReadOnlyTest}).
 */
package com.finalysis.chat.tool;
