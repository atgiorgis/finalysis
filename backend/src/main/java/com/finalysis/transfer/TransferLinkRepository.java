package com.finalysis.transfer;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransferLinkRepository extends JpaRepository<TransferLink, Long> {

    /** The link the txn is on, on either side; at most one (V3). */
    @Query("select l from TransferLink l where l.outTxn.id = :txnId or l.inTxn.id = :txnId")
    Optional<TransferLink> findByTxnId(@Param("txnId") long txnId);

    /** Every link with both sides and their accounts loaded, for building responses. */
    @Query("""
            select l from TransferLink l
            left join fetch l.outTxn o left join fetch o.account
            left join fetch l.inTxn i left join fetch i.account
            """)
    List<TransferLink> findAllWithSides();
}
