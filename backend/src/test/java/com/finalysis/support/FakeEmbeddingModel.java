package com.finalysis.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

/**
 * A deterministic {@link EmbeddingModel} for tests (docs/plans/phase-4.md §10): the vector of a text is a unit vector
 * drawn from a random generator seeded with the text's SHA-256, so the same text always gets the same vector, and two
 * different texts are nearly orthogonal (cosine similarity about 0 at 1024 dimensions). {@link #near} places a text
 * at an exact similarity to another, to control neighbours. Captures every text it is asked to embed, and reports the
 * text's length as its input tokens unless told otherwise. Thread-safe enough for one test at a time, as Spring
 * contexts share it.
 */
public class FakeEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1024;

    private final List<String> inputs = new CopyOnWriteArrayList<>();
    private final Map<String, float[]> placed = new ConcurrentHashMap<>();
    private volatile boolean reportUsage = true;
    private volatile RuntimeException failure;
    private volatile int dimensions = DIMENSIONS;

    /** Forgets inputs, placements and failures; reports usage again. For a shared context. */
    public FakeEmbeddingModel reset() {
        inputs.clear();
        placed.clear();
        reportUsage = true;
        failure = null;
        dimensions = DIMENSIONS;
        return this;
    }

    /** Places {@code text} at exactly {@code similarity} (cosine) from {@code anchor}'s vector. */
    public FakeEmbeddingModel near(String text, String anchor, double similarity) {
        float[] a = vector(anchor);
        float[] noise = hashed(text);
        // The part of the text's own vector orthogonal to the anchor, normalized.
        double dot = dot(noise, a);
        float[] orthogonal = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            orthogonal[i] = (float) (noise[i] - dot * a[i]);
        }
        normalize(orthogonal);
        double rest = Math.sqrt(1 - similarity * similarity);
        float[] v = new float[a.length];
        for (int i = 0; i < a.length; i++) {
            v[i] = (float) (similarity * a[i] + rest * orthogonal[i]);
        }
        placed.put(text, v);
        return this;
    }

    public FakeEmbeddingModel withoutUsage() {
        reportUsage = false;
        return this;
    }

    public FakeEmbeddingModel failingWith(RuntimeException failure) {
        this.failure = failure;
        return this;
    }

    /** Returns vectors of this length from now on (a misconfigured model). */
    public FakeEmbeddingModel returningDimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    /** Every text sent, in order. */
    public List<String> inputs() {
        return List.copyOf(inputs);
    }

    /** The vector this model gives {@code text}. */
    public float[] vector(String text) {
        float[] v = placed.get(text);
        return v != null ? v.clone() : hashed(text);
    }

    public static double similarity(float[] a, float[] b) {
        return dot(a, b) / Math.sqrt(dot(a, a) * dot(b, b));
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        inputs.addAll(request.getInstructions());
        if (failure != null) {
            throw failure;
        }
        List<Embedding> results = new ArrayList<>();
        int tokens = 0;
        for (String text : request.getInstructions()) {
            float[] v = vector(text);
            if (dimensions != v.length) {
                v = Arrays.copyOf(v, dimensions);
            }
            results.add(new Embedding(v, results.size()));
            tokens += text.length();
        }
        // Like Spring AI's Titan model: usage is always there, with 0 input tokens when the provider gave none.
        return new EmbeddingResponse(results, new EmbeddingResponseMetadata("fake",
                new DefaultUsage(reportUsage ? tokens : 0, 0)));
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private static float[] hashed(String text) {
        Random random = new Random(seed(text));
        float[] v = new float[DIMENSIONS];
        for (int i = 0; i < DIMENSIONS; i++) {
            v[i] = (float) random.nextGaussian();
        }
        normalize(v);
        return v;
    }

    private static long seed(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            long seed = 0;
            for (int i = 0; i < Long.BYTES; i++) {
                seed = (seed << 8) | (hash[i] & 0xff);
            }
            return seed;
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += (double) a[i] * b[i];
        }
        return sum;
    }

    private static void normalize(float[] v) {
        double norm = Math.sqrt(dot(v, v));
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) (v[i] / norm);
        }
    }
}
