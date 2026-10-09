package com.finalysis.ingestion;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TxnRepository extends JpaRepository<Txn, Long> {
}
