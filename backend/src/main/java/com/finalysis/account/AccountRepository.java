package com.finalysis.account;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    /**
     * Inserts the account unless one with the same identity exists (V12 {@code uq_account_identity}).
     * Empty when it exists. A concurrent insert of the same identity waits on the index, so exactly
     * one caller gets an id; unlike a failed insert, the transaction stays usable for the lookup.
     */
    @Query(value = """
            INSERT INTO account (name, institution, type, last_four)
            VALUES (:name, :institution, :type, :lastFour)
            ON CONFLICT (lower(btrim(institution)), type, last_four) DO NOTHING
            RETURNING id""", nativeQuery = true)
    Optional<Long> insertIfAbsent(@Param("name") String name, @Param("institution") String institution,
            @Param("type") String type, @Param("lastFour") String lastFour);

    /** The account with this identity, compared the way {@code uq_account_identity} compares it. */
    @Query(value = """
            SELECT id FROM account
            WHERE lower(btrim(institution)) = lower(btrim(:institution)) AND type = :type AND last_four = :lastFour
            """, nativeQuery = true)
    Optional<Long> findIdByIdentity(@Param("institution") String institution, @Param("type") String type,
            @Param("lastFour") String lastFour);
}
