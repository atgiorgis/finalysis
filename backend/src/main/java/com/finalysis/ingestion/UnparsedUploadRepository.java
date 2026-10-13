package com.finalysis.ingestion;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UnparsedUploadRepository extends JpaRepository<UnparsedUpload, Long> {

    /** The account's open upload of this file, if any (at most one: {@code uq_unparsed_upload_open}). */
    @Query("""
            select u from UnparsedUpload u
            where u.account.id = :accountId and u.fileSha256 = :sha256 and u.resolvedAt is null""")
    Optional<UnparsedUpload> findOpen(@Param("accountId") long accountId, @Param("sha256") String sha256);

    /**
     * Resolves the account's open upload of this file with the statement that imported it, using
     * the database clock like {@code created_at}. Returns the number of rows resolved (0 or 1).
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE unparsed_upload SET resolved_at = now(), resolved_statement_id = :statementId
            WHERE account_id = :accountId AND file_sha256 = :sha256 AND resolved_at IS NULL""")
    int resolveOpen(@Param("accountId") long accountId, @Param("sha256") String sha256,
                    @Param("statementId") long statementId);
}
