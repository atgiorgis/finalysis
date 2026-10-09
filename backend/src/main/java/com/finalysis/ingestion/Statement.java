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
import java.util.HashMap;
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

    @Column(nullable = false)
    private String sourceFileName;

    @Column(nullable = false)
    private String fileHash;

    /** Printed totals keyed by {@link SourceSection} name (DAT-08), used to reconcile each section (ING-10). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, BigDecimal> sectionTotals = new HashMap<>();

    @Generated
    private OffsetDateTime importedAt;

    protected Statement() {
    }

    public Statement(Account account, LocalDate periodStart, LocalDate periodEnd,
                     String sourceFileName, String fileHash) {
        this.account = account;
        this.periodStart = periodStart;
        this.periodEnd = periodEnd;
        this.sourceFileName = sourceFileName;
        this.fileHash = fileHash;
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

    public String getSourceFileName() {
        return sourceFileName;
    }

    public String getFileHash() {
        return fileHash;
    }

    public Map<String, BigDecimal> getSectionTotals() {
        return sectionTotals;
    }

    public void setSectionTotals(Map<String, BigDecimal> sectionTotals) {
        this.sectionTotals = new HashMap<>(sectionTotals);
    }

    public OffsetDateTime getImportedAt() {
        return importedAt;
    }
}
