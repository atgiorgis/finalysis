package com.finalysis.alert;

/**
 * One configured threshold that applied to an alert, by its property name under {@code finalysis.alerts.<check>}
 * (e.g. {@code min-increase-percent}, PERCENT {@code 50.0}), or under {@code finalysis.analytics.recurring} for the
 * monthly series settings the recurring checks share with the recurring charges list ({@code stable-count},
 * {@code min-occurrences}, {@code active-days}).
 */
public record AlertSetting(String name, Quantity value) {
}
