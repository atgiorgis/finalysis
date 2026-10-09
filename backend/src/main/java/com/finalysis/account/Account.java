package com.finalysis.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** A user account (DAT-01). Only the last four digits are stored, never the full number. */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String institution;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccountType type;

    // CHAR(4) in the schema; without this, validation expects varchar.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 4)
    private String lastFour;

    // Set by the database default; read back after insert.
    @Generated
    private OffsetDateTime createdAt;

    protected Account() {
    }

    public Account(String name, String institution, AccountType type, String lastFour) {
        this.name = name;
        this.institution = institution;
        this.type = type;
        this.lastFour = lastFour;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getInstitution() {
        return institution;
    }

    public AccountType getType() {
        return type;
    }

    public String getLastFour() {
        return lastFour;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
