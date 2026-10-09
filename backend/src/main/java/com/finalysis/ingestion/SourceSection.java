package com.finalysis.ingestion;

/** Values of ck_txn_source_section; names must match the CHECK constraint exactly. */
public enum SourceSection {
    DEPOSIT, WITHDRAWAL, CHECK, FEE, PAYMENT, PURCHASE, INTEREST, OTHER
}
