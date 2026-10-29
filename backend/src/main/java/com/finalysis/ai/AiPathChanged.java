package com.finalysis.ai;

/**
 * Published after a path switch, once the new path holds (docs/plans/phase-9.md §4.1). When the embedding model changed
 * and the new path is usable, categorization refreshes the chat search index for it; the other model's vectors are kept,
 * so switching back costs nothing.
 *
 * @param embeddingModel the new path's embedding model; null when it isn't usable
 */
public record AiPathChanged(AiPath path, String embeddingModel, boolean embeddingModelChanged) {
}
