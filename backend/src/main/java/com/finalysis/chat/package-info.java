/**
 * The chatbot (Phase 6, docs/plans/phase-6.md): a backend API that answers questions about the user's own data. The
 * model routes and writes; it never computes or acts. Around it, in Java: the outbound projection and its guard
 * (§12), the read-only tools ({@code com.finalysis.chat.tool}, §3), the number-grounding check (§5), citations resolved
 * locally to statement lines (§6), conversation memory (§7) and the per-turn limits (§8).
 *
 * <p>Read-only by construction (SEC-03, §9): nothing here depends on a writer of another feature, on {@code categorize},
 * or on the ingestion, review, transfer, transaction or account packages; the chat tables have one writer. Enforced by
 * {@code ChatReadOnlyTest}. Step 1 creates the packages and the rules; the code arrives from step 2.
 */
package com.finalysis.chat;
