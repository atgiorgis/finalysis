package com.finalysis.ingestion;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
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

    /** The account's statements that end on or before {@code date}, latest end first (ties: newest id). */
    @Query("""
            select s from Statement s
            where s.account.id = :accountId and s.id <> :excludedId and s.periodEnd <= :date
            order by s.periodEnd desc, s.id desc""")
    List<Statement> findEndingBy(@Param("accountId") long accountId, @Param("excludedId") long excludedId,
                                 @Param("date") LocalDate date, Limit limit);

    /** The account's statements that start on or after {@code date}, earliest start first (ties: oldest id). */
    @Query("""
            select s from Statement s
            where s.account.id = :accountId and s.id <> :excludedId and s.periodStart >= :date
            order by s.periodStart, s.id""")
    List<Statement> findStartingFrom(@Param("accountId") long accountId, @Param("excludedId") long excludedId,
                                     @Param("date") LocalDate date, Limit limit);

    /** Newest period first; {@code status} null for all. */
    @Query("""
            select s from Statement s
            where s.account.id = :accountId and (:status is null or s.status = :status)
            order by s.periodStart desc, s.periodEnd desc, s.id desc""")
    List<Statement> findForAccount(@Param("accountId") long accountId, @Param("status") StatementStatus status);

    /** NEEDS_REVIEW statements the user hasn't reviewed yet, across accounts, for the review queue (ING-07). */
    @Query("""
            select s from Statement s join fetch s.account
            where s.status = com.finalysis.ingestion.StatementStatus.NEEDS_REVIEW and s.reviewedAt is null""")
    List<Statement> findAwaitingReview();
}
