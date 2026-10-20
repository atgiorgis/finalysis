/**
 * Rule-based, explainable alerts (ANA-04, docs/plans/phase-5.md §6), computed on demand from the analytics figures.
 * Only dismissals are stored. Built in Phase 5 steps 5 and 6; it depends on {@code analytics}, never the reverse,
 * and uses no AI.
 */
package com.finalysis.alert;
