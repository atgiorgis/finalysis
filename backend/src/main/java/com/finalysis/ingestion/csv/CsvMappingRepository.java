package com.finalysis.ingestion.csv;

import org.springframework.data.jpa.repository.JpaRepository;

/** Mappings keyed by account id. */
public interface CsvMappingRepository extends JpaRepository<CsvMapping, Long> {
}
