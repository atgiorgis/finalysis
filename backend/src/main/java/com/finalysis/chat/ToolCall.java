package com.finalysis.chat;

/**
 * A tool call as the model sent it: a name and its arguments as JSON text. Both are untrusted; neither is ever logged,
 * stored in an error or echoed back.
 */
public record ToolCall(String name, String arguments) {
}
