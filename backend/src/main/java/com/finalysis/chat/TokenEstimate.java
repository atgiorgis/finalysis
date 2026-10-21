package com.finalysis.chat;

import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

/**
 * A prompt's input tokens, estimated before it is sent (docs/plans/phase-6.md §8, AI-05): one token per three
 * characters, rounded up, plus a few per message. Deliberately high for English and JSON (about four characters a
 * token), so the per-call cap and the cost check err on the side of stopping. Never used as a reported count.
 */
final class TokenEstimate {

    static final int CHARS_PER_TOKEN = 3;
    static final int PER_MESSAGE = 5;

    private TokenEstimate() {
    }

    /**
     * @param fixedChars characters sent besides the messages (the tool definitions)
     */
    static int of(List<Message> messages, int fixedChars) {
        long chars = fixedChars;
        for (Message message : messages) {
            chars += chars(message);
        }
        return (int) Math.min(Integer.MAX_VALUE,
                (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + (long) PER_MESSAGE * messages.size());
    }

    /** One message of this text: the history window's measure (§7.1). */
    static int ofText(String text) {
        return (length(text) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN + PER_MESSAGE;
    }

    private static long chars(Message message) {
        long chars = message.getText() == null ? 0 : message.getText().length();
        if (message instanceof AssistantMessage assistant) {
            for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                chars += length(call.id()) + length(call.name()) + length(call.arguments());
            }
        }
        if (message instanceof ToolResponseMessage tools) {
            for (ToolResponseMessage.ToolResponse response : tools.getResponses()) {
                chars += length(response.id()) + length(response.name()) + length(response.responseData());
            }
        }
        return chars;
    }

    private static int length(String text) {
        return text == null ? 0 : text.length();
    }
}
