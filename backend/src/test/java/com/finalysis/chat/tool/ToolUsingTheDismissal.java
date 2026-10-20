package com.finalysis.chat.tool;

import com.finalysis.alert.AlertDismissalService;

/** A rule fixture (ChatReadOnlyTest): a tool reaching the alert dismissal, the one write of Phase 5. */
public class ToolUsingTheDismissal {

    void dismiss(AlertDismissalService dismissals) {
        dismissals.undismiss("category-spike.2026-08.4b1f0c9a7e2d3851");
    }
}
