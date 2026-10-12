package com.finalysis.ingestion;

import com.finalysis.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One imported statement file for one account and period. */
@Entity
@Table(name = "statement")
public class Statement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private LocalDate periodStart;

    @Column(nullable = false)
    private LocalDate periodEnd;

    @Column(precision = 12, scale = 2)
    private BigDecimal openingBalance;

    @Column(precision = 12, scale = 2)
    private BigDecimal closingBalance;

    // Field defaults mirror the column defaults.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementStatus status = StatementStatus.IMPORTED;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BalanceCheck balanceCheck = BalanceCheck.UNVERIFIED;

    /** Rows parsed from the file, duplicates included; null for statements imported before V7. */
    private Integer parsedCount;

    /** Sum of the parsed rows' amounts, duplicates included; null with {@link #parsedCount}. */
    @Column(precision = 14, scale = 2)
    private BigDecimal parsedAmountTotal;

    @Column(nullable = false)
    private String sourceFileName;

    @Column(nullable = false)
    private String fileHash;

    /** {@link StatementParser#id()} of the parser that read the file. */
    @Column(nullable = false)
    private String parser;

    /**
     * Printed totals per section (DAT-08), used to reconcile each section (ING-10). Stored as a JSON
     * object keyed by {@link SourceSection} name; the column itself enforces neither the keys nor
     * NUMERIC(12,2), so {@link #setSectionTotals} validates on write.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<SourceSection, BigDecimal> sectionTotals = new HashMap<>();

    /**
     * Warnings from the import and reconciliation: line numbers, counts, ids and, for balance
     * checks, amounts; never descriptions (SEC-10). They explain the status; the informational
     * "no new rows" and "balance unverified" warnings don't require review.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> importWarnings = new ArrayList<>();

    @Generated
    private OffsetDateTime importedAt;

    protected Statement() {
    }

    public Statement(Account account, LocalDate periodStart, LocalDate periodEnd,
                     String sourceFileName, String fileHash, String parser) {
        this.account = account;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.sourceFileName = sourceFileName;
        this.fileHash = fileHash;
        this.parser = parser;
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public LocalDate getPeriodStart() {
        return periodStart;
    }

    public LocalDate getPeriodEnd() {
        return periodEnd;
    }

    public BigDecimal getOpeningBalance() {
        return openingBalance;
    }

    public void setOpeningBalance(BigDecimal openingBalance) {
        this.openingBalance = openingBalance;
    }

    public BigDecimal getClosingBalance() {
        return closingBalance;
    }

    public void setClosingBalance(BigDecimal closingBalance) {
        this.closingBalance = closingBalance;
    }

    public StatementStatus getStatus() {
        return status;
    }

    public void setStatus(StatementStatus status) {
        this.status = status;
    }

    public BalanceCheck getBalanceCheck() {
        return balanceCheck;
    }

    public void setBalanceCheck(BalanceCheck balanceCheck) {
        this.balanceCheck = balanceCheck;
    }

    public Integer getParsedCount() {
        return parsedCount;
    }

    public BigDecimal getParsedAmountTotal() {
        return parsedAmountTotal;
    }

    /** What the file held: its row count and the sum of its amounts, duplicates included. */
    public void setParsedRows(int count, BigDecimal amountTotal) {
        this.parsedCount = count;
        this.parsedAmountTotal = amountTotal;
    }

    public String getSourceFileName() {
        return sourceFileName;
    }

    public String getFileHash() {
        return fileHash;
    }

    public String getParser() {
        return parser;
    }

    public Map<SourceSection, BigDecimal> getSectionTotals() {
        return Collections.unmodifiableMap(sectionTotals);
    }

    /**
     * Replaces the section totals. Amounts must be scale 2 or less and fit NUMERIC(12,2); they are
     * stored with scale 2.
     *
     * @throws IllegalArgumentException if an amount is invalid
     */
    public void setSectionTotals(Map<SourceSection, BigDecimal> sectionTotals) {
        this.sectionTotals = new HashMap<>(ParsedStatement.validSectionTotals(sectionTotals));
    }

    public List<String> getImportWarnings() {
        return Collections.unmodifiableList(importWarnings);
    }

    public void setImportWarnings(List<String> importWarnings) {
        this.importWarnings = new ArrayList<>(importWarnings);
    }

    public OffsetDateTime getImportedAt() {
        return importedAt;
    }
}
