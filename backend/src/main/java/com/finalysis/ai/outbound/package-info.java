/**
 * What may leave the machine (SEC-01, SEC-08): the {@link com.finalysis.ai.outbound.Redactor}, the
 * {@link com.finalysis.ai.outbound.OutboundPayloadGuard} (an allowlist that fails closed), the user's own identifiers
 * ({@link com.finalysis.ai.outbound.RedactionContext}) and the shared {@link com.finalysis.ai.outbound.TextNormalizer}.
 * Built in Phase 4 for categorization (docs/plans/phase-4.md §5) and moved here in Phase 6 step 2 so that chat uses the
 * same code (docs/plans/phase-6.md §18 decision 5).
 *
 * <p>Depends on no feature package and on no model: categorization and chat depend on it, never the reverse. The
 * patterns come in through {@link com.finalysis.ai.outbound.OutboundRules}, which categorization's data file
 * implements. Enforced by {@code PackageDependencyTest}.
 */
package com.finalysis.ai.outbound;
