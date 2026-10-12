package com.finalysis.ingestion;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StatementRepository extends JpaRepository<Statement, Long> {

    Optional<Statement> findByFileHash(String fileHash);

    /** Ids of the account's statements whose period shares at least one day with the given one. */
    @Query("""
            select s.id from Statement s
            where s.account.id = :accountId and s.periodStart <= :end and s.periodEnd >= :start
            order by s.id""")
    List<Long> findOverlappingIds(@Param("accountId") long accountId,
                                  @Param("start") LocalDate start, @Param("end") LocalDate end);

    @Query("select distinct s.parser from Statement s where s.account.id = :accountId order by s.parser")
    List<String> findParsersUsed(@Param("accountId") long accountId);
}
