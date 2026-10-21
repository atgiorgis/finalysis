package com.finalysis.chat;

import com.finalysis.chat.outbound.ChatPayloadGuard;
import com.finalysis.chat.outbound.ToolResultWriter;
import com.finalysis.chat.tool.view.ContextBlockView;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

/**
 * The fixed start of every round (docs/plans/phase-6.md §10.1, §12.5): the system prompt, the context block inside
 * {@code <context>} as checked JSON, then the question inside {@code <question>} as a JSON string. Messages, never
 * templated text. The summary and the recent turns (CHT-06) go between the context and the question in step 7.
 *
 * <p>The context block is a second system message: Converse collects system messages into its system blocks, and a user
 * message there would break the user/assistant alternation the recent turns need.
 */
@Component
public class ChatPrompt {

    /**
     * Stored with every turn; changes whenever the system prompt or the message layout does. chat-1 was step 5; chat-2
     * adds the routing rules for search (step 6).
     */
    public static final String VERSION = "chat-2";

    static final String RESOURCE = "chat/system-prompt.txt";

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
    public List<Message> start(ChatPayloadGuard.Checked<ContextBlockView> context,
                               ChatPayloadGuard.Checked<String> question) {
        return List.of(new SystemMessage(system),
                new SystemMessage("<context>" + writer.context(context) + "</context>"),
                new UserMessage("<question>" + writer.question(question) + "</question>"));
    }
}
