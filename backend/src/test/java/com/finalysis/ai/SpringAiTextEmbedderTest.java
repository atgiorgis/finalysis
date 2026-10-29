package com.finalysis.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.TextEmbedder.Embedding;
import com.finalysis.ai.TextEmbedder.InvalidEmbeddingException;
import com.finalysis.support.FakeEmbeddingModel;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * {@link SpringAiTextEmbedder} (DAT-06, AI-06; docs/plans/phase-4.md step 8 notes) over a {@link FakeEmbeddingModel}:
 * one text per call with the role's model id, the token log line, usage that wasn't reported, and vectors that can't
 * be used.
 */
@ExtendWith(OutputCaptureExtension.class)
class SpringAiTextEmbedderTest {

    private static final AiProperties CLOUD = new AiProperties(true, AiPath.CLOUD, "test",
            new AiProperties.Roles("main-model", "chat-model", "embedding-model", null), true);

    private final FakeEmbeddingModel model = new FakeEmbeddingModel();

    /** The Cloud path over this model; without one, a path whose providers couldn't be built. */
    private static ActiveProviders provider(EmbeddingModel model) {
        return ActiveProviders.fixed(AiPath.CLOUD,
                model == null ? null : ProviderSet.cloud(CLOUD, 1024, null, model, null));
    }

    private SpringAiTextEmbedder embedder(EmbeddingModel model) {
        return new SpringAiTextEmbedder(provider(model));
    }

    @Test
    void embedsOneTextWithTheRolesModelAndLogsTheCall(CapturedOutput output) {
        AtomicReference<EmbeddingRequest> sent = new AtomicReference<>();
        EmbeddingModel capturing = new Delegating(model) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                sent.set(request);
                return super.call(request);
            }
        };

        Embedding embedding = embedder(capturing).embed("QUORVANE BAKEHOUSE");

        assertThat(sent.get().getInstructions()).containsExactly("QUORVANE BAKEHOUSE");
        assertThat(sent.get().getOptions().getModel()).isEqualTo("embedding-model");
        assertThat(embedding.vector()).containsExactly(model.vector("QUORVANE BAKEHOUSE"));
        assertThat(embedding.inputTokens()).isEqualTo("QUORVANE BAKEHOUSE".length());
        assertThat(embedding.usageReported()).isTrue();
        assertThat(output).containsPattern(
                "ai call role=embedding model=embedding-model items=1 inputTokens=18 ms=\\d+ outcome=OK");
        assertThat(output).doesNotContain("QUORVANE", "outputTokens");
    }

    @Test
    void theIdentityOfTheModel() {
        SpringAiTextEmbedder embedder = embedder(model);

        assertThat(embedder.enabled()).isTrue();
        assertThat(embedder.modelId()).isEqualTo("embedding-model");
        assertThat(embedder.dimensions()).isEqualTo(1024);
    }

    @Test
    void zeroOrMissingInputTokensAreNotReported(CapturedOutput output) {
        Embedding embedding = embedder(model.withoutUsage()).embed("PELTRIDGE BAKEHOUSE");

        assertThat(embedding.inputTokens()).isNull();
        assertThat(embedding.usageReported()).isFalse();
        assertThat(output).containsPattern("items=1 inputTokens=n/a ms=\\d+ outcome=OK");
        assertThat(SpringAiTextEmbedder.reported(null)).isFalse();
        assertThat(SpringAiTextEmbedder.reported(new EmptyUsage())).isFalse();
        assertThat(SpringAiTextEmbedder.reported(new DefaultUsage(0, 0))).isFalse();
        assertThat(SpringAiTextEmbedder.reported(new DefaultUsage(null, null))).isFalse();
        assertThat(SpringAiTextEmbedder.reported(new DefaultUsage(7, 0))).isTrue();
    }

    @Test
    void aFailureIsLoggedByClassAndRethrown(CapturedOutput output) {
        model.failingWith(new IllegalStateException("Throttled: QUORVANE BAKEHOUSE"));

        assertThatThrownBy(() -> embedder(model).embed("QUORVANE BAKEHOUSE"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(output).containsPattern("items=1 inputTokens=n/a ms=\\d+ outcome=ERROR error=IllegalStateException");
        assertThat(output).doesNotContain("Throttled", "QUORVANE");
    }

    @Test
    void aVectorOfTheWrongLengthIsRejected(CapturedOutput output) {
        model.returningDimensions(768);

        assertThatThrownBy(() -> embedder(model).embed("QUORVANE BAKEHOUSE"))
                .isInstanceOf(InvalidEmbeddingException.class)
                .hasMessage("The embedding model returned 768 dimensions, expected 1024");
        assertThat(output).contains("outcome=INVALID_OUTPUT error=InvalidEmbeddingException");
    }

    @Test
    void noVectorOrANonFiniteOneIsRejected() {
        EmbeddingModel empty = new Delegating(model) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                return new EmbeddingResponse(List.of());
            }
        };
        EmbeddingModel notANumber = new Delegating(model) {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                EmbeddingResponse response = super.call(request);
                response.getResult().getOutput()[3] = Float.NaN;
                return response;
            }
        };

        assertThatThrownBy(() -> embedder(empty).embed("ACME")).isInstanceOf(InvalidEmbeddingException.class)
                .hasMessage("The embedding model returned no single vector");
        assertThatThrownBy(() -> embedder(notANumber).embed("ACME")).isInstanceOf(InvalidEmbeddingException.class)
                .hasMessage("The embedding model returned a non-finite value");
    }

    @Test
    void offOrWithoutAModelNothingIsSent() {
        SpringAiTextEmbedder off = new SpringAiTextEmbedder(ActiveProviders.fixed(AiPath.OFF,
                ProviderSet.cloud(CLOUD, 1024, null, model, null)));
        SpringAiTextEmbedder noModel = embedder(null);

        assertThat(off.enabled()).isFalse();
        assertThat(noModel.enabled()).isFalse();
        assertThatThrownBy(() -> off.embed("ACME")).isInstanceOf(IllegalStateException.class).hasMessage("AI is off");
        assertThatThrownBy(() -> noModel.embed("ACME")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> embedder(model).embed(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThat(model.inputs()).isEmpty();
    }

    @Test
    void dimensionsMustBePositive() {
        assertThatThrownBy(() -> new EmbeddingProperties(0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** Forwards to another model; tests override what they change. */
    private static class Delegating implements EmbeddingModel {

        private final EmbeddingModel delegate;

        Delegating(EmbeddingModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            return delegate.call(request);
        }

        @Override
        public float[] embed(Document document) {
            return delegate.embed(document);
        }
    }
}
