package com.finalysis.chat.tool;

/** A rule fixture (ChatReadOnlyTest): a tool reaching the conversation store, the chat tables' writer. */
public class ToolUsingTheConversationStore {

    @SuppressWarnings("unused")
    private ChatConversationStore store;

    /** Stands in for step 5's store: the rule matches by simple name. */
    static class ChatConversationStore {
    }
}
