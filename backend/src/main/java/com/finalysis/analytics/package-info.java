/**
 * Analytics (docs/plans/phase-5.md): monthly totals, coverage, trends, merchants and recurring charges, all read-only
 * and computed on demand in SQL from {@code txn}, {@code category}, {@code statement} and {@code transfer_link}.
 *
 * <p>Every figure starts from one classification of each txn into a {@link com.finalysis.analytics.TxnBucket},
 * written once in {@link com.finalysis.analytics.TxnBucketSql}. SQL sums and counts; Java does every division, with
 * {@code HALF_EVEN}. No AI: this package depends on no model, categorization, ingestion or transfer class (GR-01).
 */
package com.finalysis.analytics;
