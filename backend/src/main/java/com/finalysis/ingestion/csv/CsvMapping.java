package com.finalysis.ingestion.csv;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.CurrentTimestamp;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

/**
 * How to read one account's CSV exports (ING-01). One mapping per account, replaced as a whole by
 * {@code PUT /api/accounts/{id}/csv-mapping}. Columns are header names, or zero-based indexes
 * written as text ({@code "0"}) when the file has no header row.
 *
 * <p><b>Fingerprint lock.</b> CSV rows are fingerprinted from the printed date, the joined
 * description columns and the sign-normalized amount (ING-03). Once an import has used the mapping
 * ({@link #getUsedAt()} is set), changing what feeds those values would give rows imported earlier
 * new fingerprints, so overlapping re-imports would insert them again. These fields are therefore
 * locked ({@link #lockedFieldsChangedBy}):
 * <ul>
 *   <li>{@code sign};</li>
 *   <li>{@code descriptionColumns}, both which columns and their order;</li>
 *   <li>the printed-date column: {@code postDateColumn} when present, otherwise {@code dateColumn}.</li>
 * </ul>
 * Values are compared exactly, so renaming a locked column also counts as a change. A request
 * that changes a locked field is refused unless the client confirms it; the import of an
 * overlapping period then lands in NEEDS_REVIEW, because it inserts rows.
 *
 * <p>Everything else stays editable: it only locates or parses values, without changing them.
 * That covers the columns of other roles (transaction date when a post date is mapped, amount,
 * debit and credit, the amount mode), {@code dateFormat}, {@code delimiter}, {@code encoding},
 * {@code headerRow}, {@code skipRows}, the balance and check-number columns, and
 * {@code headerSignature}.
 */
@Entity
@Table(name = "csv_mapping")
public class CsvMapping {

    @Id
    private Long accountId;

    @Column(nullable = false)
    private String delimiter;

    @Column(nullable = false)
    private String encoding;

    @Column(nullable = false)
    private boolean headerRow;

    @Column(nullable = false)
    private int skipRows;

    @Column(nullable = false)
    private String headerSignature;

    @Column(nullable = false)
    private String dateColumn;

    @Column(nullable = false)
    private String dateFormat;

    private String postDateColumn;

    // TEXT[]; an array field, because Hibernate maps a List<String> to JSON.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private String[] descriptionColumns;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CsvAmountMode amountMode;

    private String amountColumn;

    private String debitColumn;

    private String creditColumn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CsvSign sign;

    private String balanceColumn;

    private String checkNumberColumn;

    /** Set by the first successful import (step 7); null means the mapping is not locked yet. */
    private OffsetDateTime usedAt;

    @Generated
    private OffsetDateTime createdAt;

    @CurrentTimestamp(event = {EventType.INSERT, EventType.UPDATE}, source = SourceType.DB)
    private OffsetDateTime updatedAt;

    protected CsvMapping() {
    }

    CsvMapping(long accountId, CsvMappingValues values) {
        this.accountId = accountId;
        replace(values);
    }

    /** Replaces every mapping field; {@code usedAt} and {@code createdAt} are kept. */
    void replace(CsvMappingValues values) {
        delimiter = values.delimiter();
        encoding = values.encoding();
        headerRow = values.headerRow();
        skipRows = values.skipRows();
        headerSignature = values.headerSignature();
        dateColumn = values.dateColumn();
        dateFormat = values.dateFormat();
        postDateColumn = values.postDateColumn();
        descriptionColumns = values.descriptionColumns().toArray(String[]::new);
        amountMode = values.amountMode();
        amountColumn = values.amountColumn();
        debitColumn = values.debitColumn();
        creditColumn = values.creditColumn();
        sign = values.sign();
        balanceColumn = values.balanceColumn();
        checkNumberColumn = values.checkNumberColumn();
    }

    /**
     * The locked fields (API names) that {@code values} would change, sorted; empty when the mapping
     * has not been used yet or no locked field changes. A changed printed-date column is reported as
     * {@code postDateColumn} when either side maps a post date, otherwise as {@code dateColumn}.
     */
    List<String> lockedFieldsChangedBy(CsvMappingValues values) {
        if (usedAt == null) {
            return List.of();
        }
        List<String> changed = new ArrayList<>();
        if (!getDescriptionColumns().equals(values.descriptionColumns())) {
            changed.add("descriptionColumns");
        }
        if (!printedDateColumn().equals(values.printedDateColumn())) {
            changed.add(postDateColumn != null || values.postDateColumn() != null ? "postDateColumn" : "dateColumn");
        }
        if (sign != values.sign()) {
            changed.add("sign");
        }
        return changed.stream().sorted().toList();
    }

    /** The column whose value is the printed date in fingerprints: the post date when mapped. */
    public String printedDateColumn() {
        return Objects.requireNonNullElse(postDateColumn, dateColumn);
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getDelimiter() {
        return delimiter;
    }

    public String getEncoding() {
        return encoding;
    }

    public boolean isHeaderRow() {
        return headerRow;
    }

    public int getSkipRows() {
        return skipRows;
    }

    public String getHeaderSignature() {
        return headerSignature;
    }

    public String getDateColumn() {
        return dateColumn;
    }

    public String getDateFormat() {
        return dateFormat;
    }

    public String getPostDateColumn() {
        return postDateColumn;
    }

    public List<String> getDescriptionColumns() {
        return List.of(descriptionColumns);
    }

    public CsvAmountMode getAmountMode() {
        return amountMode;
    }

    public String getAmountColumn() {
        return amountColumn;
    }

    public String getDebitColumn() {
        return debitColumn;
    }

    public String getCreditColumn() {
        return creditColumn;
    }

    public CsvSign getSign() {
        return sign;
    }

    public String getBalanceColumn() {
        return balanceColumn;
    }

    public String getCheckNumberColumn() {
        return checkNumberColumn;
    }

    public OffsetDateTime getUsedAt() {
        return usedAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
