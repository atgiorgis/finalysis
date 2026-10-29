package com.finalysis.chat.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finalysis.ai.AiPath;
import com.finalysis.chat.eval.ChatEvalSet.Dataset;
import com.finalysis.chat.eval.ChatEvalSet.Question;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Where an evaluation run goes (docs/plans/phase-9.md §4.3): {@code -Deval.provider}, the Ollama base URL reduced to
 * its host (anything more is refused before the run), the path the host gives, and {@code -Deval.questions} subsets.
 */
class ChatEvalTargetTest {

    @Test
    void bedrockIsTheDefaultAndTakesNoBaseUrl() {
        assertThat(ChatEvalTarget.parse(null, null)).isEqualTo(ChatEvalTarget.BEDROCK);
        assertThat(ChatEvalTarget.parse(" ", "")).isEqualTo(ChatEvalTarget.BEDROCK);
        assertThat(ChatEvalTarget.parse("Bedrock", null).path()).isEqualTo(AiPath.CLOUD);
        assertThatThrownBy(() -> ChatEvalTarget.parse("bedrock", "http://127.0.0.1:11434"))
                .isInstanceOf(ChatEvalGuards.EvalRefusedException.class).hasMessageContaining("-Deval.provider=ollama");
        assertThatThrownBy(() -> ChatEvalTarget.parse("vertex", null))
                .isInstanceOf(ChatEvalGuards.EvalRefusedException.class).hasMessageContaining("bedrock or ollama");
    }

    @Test
    void loopbackIsLocalAndAnyOtherHostIsSelfHosted() {
        ChatEvalTarget local = ChatEvalTarget.parse("ollama", "http://127.0.0.1:11434");
        ChatEvalTarget server = ChatEvalTarget.parse("OLLAMA", "http://finalysis-selfhost:11434/");

        assertThat(local).extracting(ChatEvalTarget::path, ChatEvalTarget::host)
                .containsExactly(AiPath.LOCAL, "127.0.0.1");
        assertThat(ChatEvalTarget.parse("ollama", "http://localhost").path()).isEqualTo(AiPath.LOCAL);
        assertThat(server).extracting(ChatEvalTarget::path, ChatEvalTarget::host)
                .containsExactly(AiPath.SELF_HOSTED, "finalysis-selfhost");
        assertThat(server.ollama()).isTrue();
        assertThat(server.isSubset()).isFalse();
    }

    /** Only the host is ever recorded, so anything the host isn't (user info, a query, a path, another port) is refused. */
    @ParameterizedTest
    @ValueSource(strings = {"", "127.0.0.1:11434", "https://finalysis-selfhost:11434", "http://user:secret@host:11434",
            "http://host:11434?token=x", "http://host:11434#frag", "http://host:11434/api", "http://host:8080",
            "http://:11434", "http://bad_host:11434", "not a url at all", "ftp://host"})
    void aBaseUrlWithMoreThanAHostIsRefused(String url) {
        assertThatThrownBy(() -> ChatEvalTarget.parse("ollama", url))
                .isInstanceOf(ChatEvalGuards.EvalRefusedException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("secret").doesNotContain("token=x"));
    }

    @Test
    void theHostIsKeptAndNothingElse() {
        assertThat(ChatEvalTarget.host("http://GPU1.tail1234.ts.net:11434")).isEqualTo("gpu1.tail1234.ts.net");
    }

    private static ChatEvalSet dev() {
        return new ChatEvalSet("dev", 6, "abc", Map.of("SAMPLE", new Dataset("SAMPLE", LocalDate.of(2026, 10, 15))),
                List.of(), List.of(question("d-l01"), question("d-c01"), question("d-t01")));
    }

    private static Question question(String id) {
        return new Question(id, "assistant", "LOOKUP", "SAMPLE", List.of(), List.of());
    }

    @Test
    void aSubsetIsNamedQuestionsInTheSetsOrder() {
        List<String> subset = ChatEvalTarget.subset("d-t01, d-l01,,d-l01", dev());

        assertThat(subset).containsExactly("d-l01", "d-t01");
        assertThat(ChatEvalTarget.only(dev(), subset).questions()).extracting(Question::id)
                .containsExactly("d-l01", "d-t01");
        assertThat(ChatEvalTarget.subset("", dev())).isEmpty();
        assertThat(ChatEvalTarget.only(dev(), List.of()).questions()).hasSize(3);
        assertThat(ChatEvalTarget.BEDROCK.withSubset(subset).isSubset()).isTrue();
    }

    @Test
    void aSubsetNamingAnUnknownQuestionIsRefused() {
        assertThatThrownBy(() -> ChatEvalTarget.subset("d-l01,d-x99", dev()))
                .isInstanceOf(ChatEvalGuards.EvalRefusedException.class).hasMessageContaining("d-x99");
        assertThatThrownBy(() -> ChatEvalTarget.subset(" , ", dev()))
                .isInstanceOf(ChatEvalGuards.EvalRefusedException.class);
    }
}
