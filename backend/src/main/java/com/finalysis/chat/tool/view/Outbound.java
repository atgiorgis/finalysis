package com.finalysis.chat.tool.view;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares what a view record's component carries (docs/plans/phase-6.md §12.3). On a list, it applies to each element.
 * Components that are view records, or lists of them, carry no kind: the guard walks into them.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Outbound {

    OutboundKind value();
}
