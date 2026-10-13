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

    /** The statement's txns on a transfer link ({@code is_transfer}, kept by the V9 trigger). */
    @Query("select count(t) from Txn t where t.statement.id = :statementId and t.isTransfer = true")
    long countTransfersByStatementId(@Param("statementId") long statementId);

    /** Rows owned per statement of the account, as [statement id, count] pairs. */
    @Query("select t.statement.id, count(t) from Txn t where t.account.id = :accountId group by t.statement.id")
    List<Object[]> countByStatementForAccount(@Param("accountId") long accountId);

    /** The txns with their accounts loaded, in no particular order. */
    @Query("select t from Txn t join fetch t.account where t.id in :ids")
    List<Txn> findWithAccountByIdIn(@Param("ids") Collection<Long> ids);
}
