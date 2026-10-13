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
import java.time.OffsetDateTime;
import org.hibernate.annotations.Generated;

/**
 * A PDF upload that no layout parser recognized (ING-13): nothing was imported, and the file waits
 * for a later decision (a new parser, OCR in Phase 8, or dismissal). Only the file's hash
 * and cleaned name are kept, never its bytes or text. See {@code V8__unparsed_upload.sql}.
 */
@Entity
@Table(name = "unparsed_upload")
public class UnparsedUpload {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    /** Lowercase hex SHA-256 of the file, as {@link ImportFile#sha256()}. */
    @Column(name = "file_sha256", nullable = false)
    private String fileSha256;

    /** Cleaned like {@code statement.source_file_name}. */
    @Column(nullable = false)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UnparsedReason reason;

    @Generated
    private OffsetDateTime createdAt;

    /** Set when the upload is resolved (re-uploaded and imported) or dismissed. */
    private OffsetDateTime resolvedAt;

    /** The statement that resolved it; null when open, dismissed, or after that statement is deleted. */
    private Long resolvedStatementId;

    protected UnparsedUpload() {
    }

    public UnparsedUpload(Account account, String fileSha256, String fileName, UnparsedReason reason) {
        this.account = account;
        this.fileSha256 = fileSha256;
        this.fileName = fileName;
        this.reason = reason;
    }

    public Long getId() {
        return id;
    }

    public Account getAccount() {
        return account;
    }

    public String getFileSha256() {
        return fileSha256;
    }

    public String getFileName() {
        return fileName;
    }

    public UnparsedReason getReason() {
        return reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public Long getResolvedStatementId() {
        return resolvedStatementId;
    }

    /** Id, reason and a hash prefix only: the file name can identify the user (SEC-10). */
    @Override
    public String toString() {
        return "UnparsedUpload[" + id + ", " + reason + ", sha256="
                + (fileSha256 == null ? null : fileSha256.substring(0, Math.min(12, fileSha256.length()))) + "]";
    }
}
