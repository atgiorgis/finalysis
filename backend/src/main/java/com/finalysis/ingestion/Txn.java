package com.finalysis.ingestion;

import com.finalysis.account.Account;
import com.finalysis.categorize.Category;
import com.finalysis.categorize.CategorySource;
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
import org.hibernate.annotations.Generated;

/**
 * One statement row (DAT-03 to DAT-05, DAT-07, DAT-08). Negative amount = money out.
 *
 * <p>{@code account} and {@code statement} are separate columns; the composite foreign key
 * fk_txn_statement_account guarantees they name the same account.
 */
@Entity
@Table(name = "txn")
public class Txn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "statement_id", nullable = false)
    private Statement statement;

    @Column(nullable = false)
    private LocalDate txnDate;

    private LocalDate postDate;

    @Column(nullable = false)
    private String rawDescription;

    private String merchant;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SourceSection sourceSection;

    @Column(length = 20)
    private String checkNumber;

    /** Person-to-person payee or payer. Local only: never sent to a cloud model. */
    private String counterparty;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    private CategorySource categorySource;

    @Column(nullable = false)
    private boolean isTransfer = false;

    @Column(nullable = false)
    private String fingerprint;

    private Integer sourceLineNumber;

    private String sourceLineText;

    @Generated
    private OffsetDateTime createdAt;

    protected Txn() {
    }

    public Txn(Account account, Statement statement, LocalDate txnDate, String rawDescription,
               BigDecimal amount, SourceSection sourceSection, String fingerprint) {
        this.account = account;
        this.statement = statement;
        this.txnDate = txnDate;
        this.rawDescription = rawDescription;
        this.amount = amount;
        this.sourceSection = sourceSection;
        this.fingerprint = fingerprint;
    }

    /** Sets category and source together, as ck_txn_category_has_source requires. */
    public void categorize(Category category, CategorySource source) {
        this.category = category;
        this.categorySource = source;
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public Statement getStatement() {
        return statement;
    }

    public LocalDate getTxnDate() {
        return txnDate;
    }

    public LocalDate getPostDate() {
        return postDate;
    }

    public void setPostDate(LocalDate postDate) {
        this.postDate = postDate;
    }

    public String getRawDescription() {
        return rawDescription;
    }

    public String getMerchant() {
        return merchant;
    }

    public void setMerchant(String merchant) {
        this.merchant = merchant;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public SourceSection getSourceSection() {
        return sourceSection;
    }

    public String getCheckNumber() {
        return checkNumber;
    }

    public void setCheckNumber(String checkNumber) {
        this.checkNumber = checkNumber;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public void setCounterparty(String counterparty) {
        this.counterparty = counterparty;
    }

    public Category getCategory() {
        return category;
    }

    public CategorySource getCategorySource() {
        return categorySource;
    }

    public boolean isTransfer() {
        return isTransfer;
    }

    public void setTransfer(boolean transfer) {
        this.isTransfer = transfer;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public Integer getSourceLineNumber() {
        return sourceLineNumber;
    }

    public void setSourceLineNumber(Integer sourceLineNumber) {
        this.sourceLineNumber = sourceLineNumber;
    }

    public String getSourceLineText() {
        return sourceLineText;
    }

    public void setSourceLineText(String sourceLineText) {
        this.sourceLineText = sourceLineText;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
