package com.finalysis.ingestion.csv;

import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Mappings keyed by account id. */
public interface CsvMappingRepository extends JpaRepository<CsvMapping, Long> {

    /** The mapping's version ({@link CsvMappingSpec#updatedAt()}), or empty when it has none. */
    @Query("select m.updatedAt from CsvMapping m where m.accountId = :accountId")
    Optional<OffsetDateTime> findUpdatedAt(@Param("accountId") long accountId);

    /**
     * Sets {@code used_at} on the first import that uses the mapping. Plain SQL, so
     * {@code updated_at} stays as it is: marking a mapping used doesn't change how it reads files,
     * and must not make imports that parsed with it look stale.
     */
    @Modifying
    @Query(value = "UPDATE csv_mapping SET used_at = now() WHERE account_id = :accountId AND used_at IS NULL",
            nativeQuery = true)
    int markUsed(@Param("accountId") long accountId);
}
