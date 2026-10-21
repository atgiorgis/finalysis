package com.finalysis.ai.outbound;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The patterns {@link Redactor} needs (docs/plans/phase-6.md §18, "The move"). They live in categorization's data file
 * ({@code categorize/merchant-cleaning.yml}), which categorization loads and implements this with, so this package
 * depends on no feature.
 */
public interface OutboundRules {

    /** Identifiers removed wherever they match (ACH names and ids, confirmation, reference and trace numbers). */
    List<Pattern> redactPatterns();

    /** Person-to-person formats; a payment that matches is never sent. */
    List<Pattern> personToPerson();

    /** Two-letter state codes that end a location tail. */
    Set<String> locationStates();

    /** First words of multi-word city names, dropped from the outbound copy after a city. */
    Set<String> locationCityPrefixes();
}
