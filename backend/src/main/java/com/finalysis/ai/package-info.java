/**
 * AI wiring shared by the features that use a model (docs/plans/phase-4.md §7): the {@link com.finalysis.ai.AiMode},
 * the model id per role ({@link com.finalysis.ai.AiProperties}), the startup checks, the status block behind the
 * UI's cloud banner, and the token log every call writes (AI-06).
 *
 * <p>Code here and in the features depends only on Spring AI's {@code ChatModel} and {@code EmbeddingModel}. Which
 * provider sits behind them is configuration: the {@code aws} profile turns on Bedrock; without a profile there is
 * no model at all.
 */
package com.finalysis.ai;
