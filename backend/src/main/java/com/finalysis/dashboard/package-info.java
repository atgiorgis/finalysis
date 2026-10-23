/**
 * The dashboard's reads (docs/plans/phase-7.md §3): what the Overview and its details panel show, bundled from the
 * analytics and alert query services in one read-only transaction. Comparisons and Other's slice are computed here in
 * Java; nothing is stored, and nothing depends on this package ({@code DashboardReadOnlyTest}).
 */
package com.finalysis.dashboard;
