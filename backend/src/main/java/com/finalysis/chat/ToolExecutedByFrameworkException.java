package com.finalysis.chat;

/**
 * Thrown when Spring AI tries to run a chat tool itself (docs/plans/phase-6.md §10.1). Chat tools run only through
 * {@link ToolExecutor}, which checks the arguments, opens the read-only transaction and sends only guarded results; the
 * callbacks given to Spring AI carry the definitions and refuse to run, so a framework that executes tool calls fails
 * closed. Step 5 proves the loop gets the calls back unexecuted.
 */
public class ToolExecutedByFrameworkException extends IllegalStateException {

    public ToolExecutedByFrameworkException(String tool) {
        super("Spring AI tried to run the chat tool " + tool + "; chat tools run only through ToolExecutor");
    }
}
