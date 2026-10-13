package com.finalysis.transfer;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TransferRejectionRepository extends JpaRepository<TransferRejection, TransferRejection.Pair> {
}
