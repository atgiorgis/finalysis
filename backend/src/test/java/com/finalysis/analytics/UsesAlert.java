package com.finalysis.analytics;

import com.finalysis.alert.AlertQueryService;

/** A deliberate violation for {@code PackageDependencyTest}: analytics code that depends on the alerts. Test-only. */
@SuppressWarnings("unused")
public class UsesAlert {
    private AlertQueryService alerts;
}
