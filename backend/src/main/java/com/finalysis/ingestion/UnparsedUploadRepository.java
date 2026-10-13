package com.finalysis.ingestion;

import java.util.List;
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

    /** Every open upload with its account, for the review queue (ING-07). */
    @Query("select u from UnparsedUpload u join fetch u.account where u.resolvedAt is null")
    List<UnparsedUpload> findAllOpenWithAccount();

    /**
     * Resolves the account's open upload of this file with the statement that imported it, using
     * the database clock like {@code created_at}. Returns the number of rows resolved (0 or 1).
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            UPDATE unparsed_upload
            SET resolved_at = now(), resolution = 'RESOLVED', resolved_statement_id = :statementId
            WHERE account_id = :accountId AND file_sha256 = :sha256 AND resolved_at IS NULL""")
    int resolveOpen(@Param("accountId") long accountId, @Param("sha256") String sha256,
                    @Param("statementId") long statementId);

    /**
     * Dismisses the upload if it is still open: {@code resolved_at} on the database clock, resolution
     * DISMISSED and no statement (V11). Returns the number of rows dismissed (0 or 1), so two
     * dismissals can't both win.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = """
            UPDATE unparsed_upload SET resolved_at = now(), resolution = 'DISMISSED'
            WHERE id = :id AND resolved_at IS NULL""")
    int dismissOpen(@Param("id") long id);
}
