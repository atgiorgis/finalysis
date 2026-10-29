package com.finalysis.ai;

import org.springframework.ai.embedding.EmbeddingModel;

/**
 * The embedding model on the active path (docs/plans/phase-9.md §3.2, §4.1): Titan V2 (1024) on Cloud,
 * nomic-embed-text (768) on Ollama. Its id is stored with every vector, and only vectors of one id are compared.
 */
public interface EmbeddingProvider {

    EmbeddingModel model();

    String modelId();

    /** The length of every vector it returns; each is checked against it. */
    int dimensions();
}
