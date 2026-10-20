package com.finalysis.chat.tool;

import com.finalysis.analytics.Arithmetic;
import java.math.BigDecimal;

/** A rule fixture (ChatReadOnlyTest): a tool doing arithmetic with an analytics helper instead of calling a service. */
public class ToolUsingAlertOnlyHelper {

    Object average(BigDecimal sum) {
        return Arithmetic.money(sum);
    }
}
