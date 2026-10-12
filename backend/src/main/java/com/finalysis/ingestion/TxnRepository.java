package com.finalysis.ingestion;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TxnRepository extends JpaRepository<Txn, Long> {

    @Query("select t.fingerprint from Txn t where t.account.id = :accountId and t.fingerprint in :fingerprints")
    List<String> findExistingFingerprints(@Param("accountId") long accountId,
                                          @Param("fingerprints") Collection<String> fingerprints);

    @Query("select distinct t.externalId from Txn t where t.account.id = :accountId and t.externalId in :externalIds")
    List<String> findExistingExternalIds(@Param("accountId") long accountId,
                                         @Param("externalIds") Collection<String> externalIds);

    long countByStatementId(long statementId);
}
