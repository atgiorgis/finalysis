package com.finalysis.chat;

import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.view.ContextBlockView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * The fixed start of every round (docs/plans/phase-6.md §10.1, §12.5), in the plan's order: the system prompt, the
 * context block inside {@code <context>} as checked JSON, the summary of older turns inside {@code <summary>} as a JSON
 * string, the recent turns (each its question inside {@code <question>}, then its answer as an assistant message, figures
 * stripped), then this turn's question inside {@code <question>} as a JSON string. Messages, never templated text.
 *
 * <p>The context block and the summary are system messages: Converse collects system messages into its system blocks,
 * and a user message there would break the user/assistant alternation the recent turns need.
 */
@Component
public class ChatPrompt {

    /**
     * Stored with every turn; changes whenever the system prompt, the tool descriptions or the message layout does.
     * chat-1 was step 5; chat-2 adds the routing rules for search (step 6); chat-3 adds the summary and the recent turns
     * (step 7); chat-4 the step 8b fixes; chat-5 search and merchant totals over every month with data by default, and
     * the cache points after the tools and the context block (step 8c); chat-6 the step 8d fixes (a second search with
     * brand names, no "let me look" without a call, higherPeriod, the trend fields named apart, searchIndex).
     */
    public static final String VERSION = "chat-6";

    static final String RESOURCE = "chat/system-prompt.txt";
    /** Where the history starts in {@link #start}'s messages: after the system prompt and the context block. */
    static final int HISTORY_START = 2;

    private final ToolResultWriter writer;
    private final String system;

    public ChatPrompt(ToolResultWriter writer) {
        this.writer = writer;
        try (InputStream in = ChatPrompt.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing");
            }
            this.system = new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String system() {
        return system;
    }

    /**
     * @param context  the context block as the guard returned it
     * @param question the outbound question, redacted and checked; a JSON string inside {@code <question>} (the
     *                 redactor already removed {@code < > { }})
     */
    public List<Message> start(ChatPayloadGuard.Checked<ContextBlockView> context, ChatHistory history,
                               ChatPayloadGuard.Checked<String> question) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(system));
        messages.add(new SystemMessage("<context>" + writer.context(context) + "</context>"));
        if (history.summary() != null) {
            messages.add(new SystemMessage("<summary>" + writer.summary(history.summary()) + "</summary>"));
        }
        for (ChatHistory.Turn turn : history.turns()) {
            messages.add(new UserMessage("<question>" + writer.question(turn.question()) + "</question>"));
            messages.add(AssistantMessage.builder().content(writer.answer(turn.answer())).build());
        }
        messages.add(new UserMessage("<question>" + writer.question(question) + "</question>"));
        return List.copyOf(messages);
    }

    /** How many of {@link #start}'s messages are history. */
    static int historyMessages(ChatHistory history) {
        return (history.summary() == null ? 0 : 1) + 2 * history.turns().size();
    }
}
