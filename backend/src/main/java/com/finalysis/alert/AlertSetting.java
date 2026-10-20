package com.finalysis.alert;

/**
 * One configured threshold that applied to an alert, by its property name under {@code finalysis.alerts.<check>}
 * (e.g. {@code min-increase-percent}, PERCENT {@code 50.0}).
 */
public record AlertSetting(String name, Quantity value) {
}
